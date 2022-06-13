package io.simplematter.mqtttestsuite.scenario.util

import io.netty.handler.codec.mqtt.{MqttQoS, MqttTopicSubscription}
import io.simplematter.mqtttestsuite.config.{ConnectionMonkeyConfig, MqttBrokerConfig}
import io.simplematter.mqtttestsuite.mqtt.MqttUtils
import io.simplematter.mqtttestsuite.model.ClientId
import io.simplematter.mqtttestsuite.stats.FlightRecorder
import io.simplematter.mqtttestsuite.util.{ErrorInjector, MessageGenerator}
import org.slf4j.LoggerFactory
import zio.{Fiber, Has, RIO, Semaphore, URIO, ZIO, ZQueue, clock}
import zio.clock.Clock

import java.nio.charset.{Charset, StandardCharsets}
import java.util.concurrent.{TimeUnit, TimeoutException}
import zio.duration.*

import scala.concurrent.Promise

class MqttConsumer private(mqttBrokerConfig: MqttBrokerConfig,
                           connectionMonkey: ConnectionMonkeyConfig,
                           val clientId: ClientId,
                           intermittent: Boolean,
                           cleanSession: Boolean,
                           qos: MqttQoS,
                           topicPatterns: Seq[String],
                           flightRecorder: FlightRecorder,
                           clientConnectMutex: Semaphore,
                           errorInjector: ErrorInjector
                          ) extends MqttConnectionCommons(mqttBrokerConfig, connectionMonkey, clientId, intermittent, cleanSession, qos, flightRecorder, clientConnectMutex) {
  import MqttConsumer.log

  private val connectedAndSubscribedPromise = Promise[Unit]()

  private def attachMessageHandlers(): URIO[Clock, Unit] = {
    URIO.runtime[Clock].map { env =>
      client.onIncomingPublish { msg =>
        val handlerEffect = for {
          now <- clock.currentTime(TimeUnit.MILLISECONDS)
          payloadStr = msg.payload().toString(StandardCharsets.UTF_8)
          _ = log.trace("Raw incoming message: {}", payloadStr)
          (messageId, sendingTimestamp) <- MessageGenerator.unpackMessagePrefix(payloadStr)
          _ = log.debug("Received a message: client={}, topic={}, id={}, sent={}, rec={}", clientId, msg.variableHeader().topicName(), messageId, sendingTimestamp, now)
          _ <- flightRecorder.messageReceived(messageId, msg.variableHeader().topicName(), clientId, sendingTimestamp, now)
        } yield ()
        env.unsafeRunToFuture(errorInjector.receiveMessage(handlerEffect))
      }
    }
  }

  def waitForSubscribe(timeout: Duration = 30.seconds): RIO[Clock, Unit] = {
    ZIO.fromPromiseScala(connectedAndSubscribedPromise).timeoutFail(TimeoutException(s"Timeout waiting for client ${clientId} connect and subscribe"))(timeout)
  }

  /**
   * Effect that runs upon connection establishing
   */
  override protected val onConnect: URIO[Clock, Unit] = {
    (for {
      _ <- ZIO {log.debug("Subscribing {} to {}", clientId, topicPatterns)}
      _ <- flightRecorder.mqttSubscribeSent(clientId, topicPatterns)
      subackCodes <- ZIO.fromFuture { implicit ec =>
        client.subscribe(topicPatterns.map(pattern => MqttTopicSubscription(pattern, qos)))
      }
      topicsWithCodes = topicPatterns.zip(subackCodes)
      _ = connectedAndSubscribedPromise.trySuccess(())
      _ <- flightRecorder.mqttSubscribeAcknowledged(clientId, topicsWithCodes)
      _ = topicsWithCodes.foreach((pattern, code) =>
        if(MqttUtils.isSubAckError(code))
          log.error(s"Suback with error code ${code} for pattern ${pattern} for MQTT client ${clientId}")
      )
    } yield ()).catchAll { err =>
      URIO.succeed {
        log.error(s"Failed to subscribe MQTT client ${clientId} to the topic patterns ${topicPatterns}", err)
      }
    }
  }
}

object MqttConsumer {
  private val log = LoggerFactory.getLogger(classOf[MqttConsumer])

  def make(mqttBrokerConfig: MqttBrokerConfig,
           connectionMonkey: ConnectionMonkeyConfig,
           clientId: ClientId,
           intermittent: Boolean,
           cleanSession: Boolean,
           qos: MqttQoS,
           topicPatterns: Seq[String],
           errorInjector: ErrorInjector
          ): URIO[Has[FlightRecorder] with Clock, MqttConsumer] = {
    for {
      flightRecorder <- ZIO.service[FlightRecorder]
      clientConnectMutex <- Semaphore.make(1)
      mqttConsumer = MqttConsumer(mqttBrokerConfig = mqttBrokerConfig,
      connectionMonkey = connectionMonkey,
      clientId = clientId,
      intermittent = intermittent,
      cleanSession = cleanSession,
      qos = qos,
      topicPatterns = topicPatterns,
      flightRecorder = flightRecorder,
      clientConnectMutex = clientConnectMutex,
      errorInjector = errorInjector
      )
      _ <- mqttConsumer.attachMessageHandlers()
    } yield mqttConsumer
  }


  def disconnectAll(consumers: Iterable[MqttConsumer]): RIO[Clock, Unit] = MqttConnectionCommons.disconnectAll(consumers)

  type WithConnection = (MqttConsumer, Fiber[Any, Any])
}