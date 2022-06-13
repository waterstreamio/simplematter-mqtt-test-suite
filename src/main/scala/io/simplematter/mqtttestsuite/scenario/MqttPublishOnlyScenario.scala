package io.simplematter.mqtttestsuite.scenario

import io.netty.handler.codec.mqtt.MqttQoS
import io.simplematter.mqtttestsuite.config.{ConnectionMonkeyConfig, MqttBrokerConfig, ScenarioConfig}
import io.simplematter.mqtttestsuite.scenario.util.MqttPublisher
import io.simplematter.mqtttestsuite.stats.FlightRecorder
import io.simplematter.mqtttestsuite.model.{ClientId, GroupedTopics, NodeId, NodeIndex}
import io.simplematter.mqtttestsuite.scenario.MqttToKafkaScenario.log
import io.simplematter.mqtttestsuite.util.{ErrorInjector, MessageGenerator}
import org.slf4j.LoggerFactory
import zio.clock.Clock
import zio.duration.*
import zio.{Fiber, Has, IO, Promise, RIO, Ref, Runtime, Task, UIO, ZIO, clock}

import java.util.concurrent.TimeUnit
import scala.concurrent.Future

/**
 * Just publishes messages to MQTT, doesn't expect them back. Doesn't measure latency or delivery to any other system.
 *
 * @param config
 */
class MqttPublishOnlyScenario(stepInterval: Duration,
                              nodeId: NodeId,
                              protected val nodeIndex: NodeIndex,
                              protected val mqttBrokerConfig: MqttBrokerConfig,
                              protected val scenarioConfig: ScenarioConfig.MqttPublishOnly,
                              protected val errorInjector: ErrorInjector
                             ) extends MqttTestScenario {
  import MqttPublishOnlyScenario.*

  override val name: String = MqttPublishOnlyScenario.name

  private val publishingClientPrefix = scenarioConfig.clientPrefix + nodeId + "-"

  override def start(): RIO[ScenarioEnv, Fiber[Any, Any]] = {
    for {
      _ <- RIO { log.info(s"Start the scenario for node ${nodeId}") }
      c <- rampUpClients()
      startTime <- clock.currentTime(TimeUnit.MILLISECONDS)
      _ <- clock.sleep(scenarioConfig.durationSeconds.seconds)
      stopTime <- clock.currentTime(TimeUnit.MILLISECONDS)
      _ = log.info(s"Stopping scenario after ${(stopTime - startTime)/1000} s")
      _ <- MqttPublisher.disconnectAll(c)
      _ = log.debug("Disconnected all MQTT clients")
      //TODO graceful shutdown of the publishers. There are some errors/warnings currently in the logs.
     } yield (Fiber.succeed(()))

  }

  private def rampUpClients(): RIO[ScenarioEnv, Seq[MqttPublisher]] = {
    val res = for {
      startTimestamp <- clock.currentTime(TimeUnit.MILLISECONDS)
      clientsNumber <- Ref.make[Int](0)
      allPublishers <- Ref.make(Seq.empty[MqttPublisher])
      rampUpComplete <- Promise.make[Nothing, Unit]
      publishingStart = if(scenarioConfig.actionsDuringRampUp) Task.succeed(()) else rampUpComplete.await
      finalizing <- Promise.make[Nothing, Unit]
      publishers <- spawnMqttPublisher(clientsNumber, publishingStart, finalizing).
        flatMap { spawnedPublisher =>
          log.debug(s"Spawned a publisher: ${spawnedPublisher}")
          allPublishers.update { p => p :+ spawnedPublisher} } .
        flatMap { _ => delayAfterPublisherSpawn(startTimestamp, clientsNumber) }.
        flatMap(_ => clientsNumber.get).
        repeatUntil(_ >= scenarioConfig.publishingClientsNumber)
      stopTimestamp <- clock.currentTime(TimeUnit.MILLISECONDS)
      _ = log.debug(s"All publishers spawned: ${publishers}, all=${allPublishers}, ramp up actual duration = ${stopTimestamp - startTimestamp}")
      _ <- rampUpComplete.succeed(())
      p <- allPublishers.get
    } yield p

    res
  }

  private def delayAfterPublisherSpawn(startTimestamp: Long, clientsNumber: Ref[Int]): RIO[Clock, Unit] = {
    for {
      n <- clientsNumber.get
      now <- clock.currentTime(TimeUnit.MILLISECONDS)
      tNext = scenarioConfig.rampUpSeconds * 1000L * n / scenarioConfig.publishingClientsNumber + startTimestamp
      delay = (if(n >= scenarioConfig.publishingClientsNumber || tNext < now) 0 else tNext - now).milliseconds
      _ = log.debug("Sleeping for {}, current clients {}", delay, n)
      _ <- clock.sleep(delay)
    } yield ()
  }

  private def spawnMqttPublisher(clientsNumber: Ref[Int], publishingStart: Task[Unit], finalizing: Promise[Nothing, Unit]): RIO[ScenarioEnv, MqttPublisher] = {
    for {
      flightRecorder <- ZIO.service[FlightRecorder]
      p <- clientsNumber.updateAndGet( _ + 1).flatMap { i =>
        val clientId = ClientId(publishingClientPrefix + i)
        log.debug(s"Spawning pub ${clientId}")
        MqttPublisher.make(mqttBrokerConfig = mqttBrokerConfig,
          connectionMonkey = ConnectionMonkeyConfig.disabled,
          clientId = clientId,
          intermittent = false,
          cleanSession = false,
          messagesPerSecond = scenarioConfig.clientMessagesPerSecond,
          messageMinSize = scenarioConfig.messageMinSize,
          messageMaxSize = scenarioConfig.messageMaxSize,
          qos = MqttQoS.valueOf(scenarioConfig.qos),
          topics = thisNodeGroupedTopics,
          expectedRecepients = (_ => None),
          errorInjector = errorInjector
        )
      }
      _ = log.debug(s"Publisher ${p.clientId} created")
      _ <- p.maintainConnection(finalizing, mqttBrokerConfig.statusCheckIntervalSeconds.seconds).fork
      _ <- publishingStart.zipRight {
        log.debug("Start sending the messages")
        p.sendMessages()
      }.fork
    } yield p
  }

}

object MqttPublishOnlyScenario {
  val name: String = "mqttPublishOnly"

  private val log = LoggerFactory.getLogger(classOf[MqttPublishOnlyScenario])
}