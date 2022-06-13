package io.simplematter.mqtttestsuite.scenario.util

import io.netty.buffer.{ByteBuf, ByteBufUtil, Unpooled}
import io.netty.handler.codec.mqtt.MqttQoS
import io.simplematter.mqtttestsuite.config.{ConnectionMonkeyConfig, MqttBrokerConfig}
import io.simplematter.mqtttestsuite.model.{ClientId, GroupedTopics, MessageId, MqttTopicName}
import io.simplematter.mqtttestsuite.mqtt.{MqttClient, MqttOptions}
import io.simplematter.mqtttestsuite.util.{ErrorInjector, MessageGenerator}
import io.simplematter.mqtttestsuite.stats.FlightRecorder
import org.slf4j.LoggerFactory
import zio.clock.Clock
import zio.duration.*
import zio.blocking.Blocking
import zio.{Fiber, Has, IO, Promise, RIO, Ref, Schedule, Semaphore, Task, UIO, URIO, ZIO, clock}

import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import scala.concurrent.TimeoutException
import scala.util.Random

class MqttPublisher private(mqttBrokerConfig: MqttBrokerConfig,
                            connectionMonkey: ConnectionMonkeyConfig,
                            val clientId: ClientId,
                            intermittent: Boolean,
                            cleanSession: Boolean,
                            messagesPerSecond: Double,
                            messageMinSize: Int,
                            messageMaxSize: Int,
                            qos: MqttQoS,
                            topics: GroupedTopics,
                            flightRecorder: FlightRecorder,
                            clientConnectMutex: Semaphore,
                            expectedRecepients: (MqttTopicName => Option[Iterable[ClientId]]),
                            errorInjector: ErrorInjector
                           ) extends MqttConnectionCommons(mqttBrokerConfig, connectionMonkey, clientId, intermittent, cleanSession, qos, flightRecorder, clientConnectMutex) {

  import MqttPublisher.log

  private val messageSendingMinInterval = (1000/messagesPerSecond).toInt.milliseconds


  /**
   * Start message sending loop. When the returned ZIO is terminated, publishing stops
   *
   * @return
   */
  def sendMessages(): RIO[Clock with Blocking, Unit] = {
    for {
      msgCounter <- Ref.make[Int](0)
      _ <- (for {
        n <-  msgCounter.updateAndGet(_ + 1)
        msgId = MessageId(clientId, n)
        now <- clock.currentTime(TimeUnit.MILLISECONDS)
        _ = log.trace("Publishing {} message {}, timestamp {}", clientId, msgId, now)
        _ <- sendMessageIfPossible(msgId, now).catchAll { err => UIO.succeed(log.error(s"Client ${clientId} failed to send MQTT message", err)) }
      } yield ()).repeat(Schedule.fixed(messageSendingMinInterval)).onInterrupt {
        (for {
          _ <- ZIO { log.debug("Interrupt sending the messages for {}", clientId) }
        } yield ()).run
      }
    } yield()
  }

  def publishInFlightCount(): Int = client.publishInFlightCount()

  private def sendMessageIfPossible(messageId: MessageId, timestamp: Long): RIO[Clock with Blocking, Unit] = {
    if(client.isConnected()) {
      for {
        _ <- Task { log.trace("Connected - sending msg {}", messageId) }
        topic <- Task { topics.randomTopic() }
        _ <- flightRecorder.recordMessageSend(messageId, errorInjector.sendMessage(ZIO.fromFuture { implicit ec =>
          client.publish(topic = topic.value,
            payload = Unpooled.copiedBuffer(MessageGenerator.generatePackedMessage(messageId, timestamp, messageMinSize, messageMaxSize), StandardCharsets.UTF_8),
            qos = qos,
            label = Option(messageId.toString)
          )._2
        }), topic, expectedRecepients(topic)).uninterruptible /* not interrupt to make sure that the statistics gets written correctly when the test shuts down */
      } yield ()
    } else {
      Task { log.debug("{} not connected - skip sending the message {}", clientId, messageId) }
    }
  }
}

object MqttPublisher {
  private val log = LoggerFactory.getLogger(classOf[MqttPublisher])

  def make(mqttBrokerConfig: MqttBrokerConfig,
           connectionMonkey: ConnectionMonkeyConfig,
           clientId: ClientId,
           intermittent: Boolean,
           cleanSession: Boolean,
           messagesPerSecond: Double,
           messageMinSize: Int,
           messageMaxSize: Int,
           qos: MqttQoS,
           topics: GroupedTopics,
           expectedRecepients: (MqttTopicName => Option[Iterable[ClientId]]),
           errorInjector: ErrorInjector
          ): URIO[Has[FlightRecorder], MqttPublisher] = {
    for {
      flightRecorder <- ZIO.service[FlightRecorder]
      clientConnectMutex <- Semaphore.make(1)
    } yield MqttPublisher(mqttBrokerConfig = mqttBrokerConfig,
      connectionMonkey = connectionMonkey,
      clientId = clientId,
      intermittent = intermittent,
      cleanSession = cleanSession,
      messagesPerSecond = messagesPerSecond,
      messageMinSize = messageMinSize,
      messageMaxSize = messageMaxSize,
      qos = qos,
      topics = topics,
      flightRecorder = flightRecorder,
      clientConnectMutex = clientConnectMutex,
      expectedRecepients = expectedRecepients,
      errorInjector = errorInjector
    )
  }

  //TODO do we need it? maintainConnection now can switch to connect-only mode for the finalization
//  def finalizeSending(publishers: Iterable[MqttPublisher]): RIO[Clock, Unit] = {
//    ZIO.collectAllPar(publishers.map(_.finalizeSending())).unit
//  }

  //TBD: do we need it? mcFiber.interrupt seems to do the job
  def disconnectAll(publishers: Iterable[MqttPublisher]): RIO[Clock, Unit] = MqttConnectionCommons.disconnectAll(publishers)

  type WithConnection = (MqttPublisher, Fiber[Any, Any])
}
