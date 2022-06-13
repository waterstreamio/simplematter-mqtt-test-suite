package io.simplematter.mqtttestsuite.scenario

import io.netty.handler.codec.mqtt.MqttQoS
import io.simplematter.mqtttestsuite.config.{ConnectionMonkeyConfig, MqttBrokerConfig, ScenarioConfig}
import io.simplematter.mqtttestsuite.scenario.util.{MqttConsumer, MqttPublisher}
import io.simplematter.mqtttestsuite.stats.FlightRecorder
import io.simplematter.mqtttestsuite.model.{ClientId, GroupedTopics, NodeId, NodeIndex}
import io.simplematter.mqtttestsuite.scenario.KafkaToMqttScenario.log
import io.simplematter.mqtttestsuite.scenario.MqttTestScenario.PostScenarioActivity
import io.simplematter.mqtttestsuite.scenario.MqttToKafkaScenario.log
import io.simplematter.mqtttestsuite.util.{ErrorInjector, MessageGenerator}
import org.slf4j.LoggerFactory
import zio.clock.Clock
import zio.duration.*
import zio.{Fiber, Has, IO, Promise, RIO, Ref, Runtime, Task, UIO, ZIO, clock}

import java.util.concurrent.TimeUnit
import scala.concurrent.Future

/**
 * Publishes messages to MQTT, reads them back from MQTT (not implemented yet)
 *
 * @param config
 */
class MqttToMqttScenario(stepInterval: Duration,
                         nodeId: NodeId,
                         protected val nodeIndex: NodeIndex,
                         protected val mqttBrokerConfig: MqttBrokerConfig,
                         protected val scenarioConfig: ScenarioConfig.MqttToMqtt,
                         protected val errorInjector: ErrorInjector) extends MqttTestScenario {
  import MqttToMqttScenario._

  override val name: String = MqttToMqttScenario.name

  private val publishingClientPrefix = scenarioConfig.publishingClientPrefix + nodeId + "-"

  private val subscribingClientPrefix = scenarioConfig.subscribingClientPrefix + nodeId + "-"

  override def start(): RIO[ScenarioEnv, PostScenarioActivity] = {
    val mqttClientsPerNode = scenarioConfig.publishingClientsPerNode + scenarioConfig.subscribingClientsPerNode
    val consumersRampUpSeconds = ((scenarioConfig.subscribingClientsPerNode.toDouble / mqttClientsPerNode)*scenarioConfig.rampUpSeconds).toInt
    val publishersRampUpSeconds = Math.max(scenarioConfig.rampUpSeconds - consumersRampUpSeconds, 0)

    for {
      _ <- ZIO.succeed { log.info(s"Starting the scenario for node ${nodeId}") }
//      scenarioStop <- Promise.make[Nothing, Unit]
      flightRecorder <- ZIO.service[FlightRecorder]
      finalizingScenario <- Promise.make[Nothing, Unit]
      _ <- flightRecorder.scenarioRampUpStarted(name, scenarioConfig.rampUpSeconds, scenarioConfig.durationSeconds)
      _ <- flightRecorder.mqttConnectionsExpected(mqttClientsPerNode)
      (mqttConsumersWithConnections, mqttTopicToClients) <- rampUpMqttConsumers(
        rampUpSeconds = consumersRampUpSeconds,
        subscribingClientPrefix = subscribingClientPrefix,
        subscribingClientsPerNode = scenarioConfig.subscribingClientsPerNode,
        subscribeTopicGroupsPerClient = scenarioConfig.subscribeTopicGroupsPerClient,
        subscribeTopicsPerClient = scenarioConfig.subscribeTopicsPerClient,
        subscribeWildcardMessageDeduplicate = scenarioConfig.subscribeWildcardMessageDeduplicate,
        qos = MqttQoS.valueOf(scenarioConfig.subscribeQos),
        connectionMonkey = scenarioConfig.subscribeConnectionMonkey,
        scenarioFinalizing = finalizingScenario
      )
      publishersWithConnections <- rampUpPublishers(//scenarioStop.await,
        scenarioFinalizing = finalizingScenario,
        rampUpSeconds = publishersRampUpSeconds,
        publishingClientsPerNode = scenarioConfig.publishingClientsPerNode,
        publishingClientPrefix = publishingClientPrefix,
        clientMessagesPerSecond = scenarioConfig.publishingClientMessagesPerSecond,
        qos = MqttQoS.valueOf(scenarioConfig.publishQos),
        connectionMonkey = scenarioConfig.publishConnectionMonkey,
        actionsDuringRampUp = scenarioConfig.actionsDuringRampUp,
        expectedRecepients = (topic => mqttTopicToClients.get(topic))
      )
      _ <- flightRecorder.scenarioRunning()
      startTime <- clock.currentTime(TimeUnit.MILLISECONDS)
      _ <- clock.sleep(scenarioConfig.durationSeconds.seconds)
//      _ <- scenarioStop.succeed(())
      _ <- finalizingScenario.succeed(())
      _ <- flightRecorder.scenarioDone()
      stopTime <- clock.currentTime(TimeUnit.MILLISECONDS)
      _ = log.info(s"Stopped scenario after ${(stopTime - startTime)/1000} s")
      publishersConnectionsFiber = Fiber.collectAll(publishersWithConnections.map { case (_, mcFiber) => mcFiber})
      consumersConnectionsFiber = Fiber.collectAll(mqttConsumersWithConnections.map { case (_, mcFiber) => mcFiber})
      _ <- publishersConnectionsFiber.await.map { res =>
        val ifmClients = publishersWithConnections.map(_._1).map(client => (client.clientId, client.publishInFlightCount())).filter(_._2 > 0)
        log.debug("Publishers interrupted. Clients with in-flight messages: {}", ifmClients)
      }.fork
     } yield ( consumersConnectionsFiber.zip(publishersConnectionsFiber) )
  }
}

object MqttToMqttScenario {
  val name: String = "mqttToMqtt"

  private val log = LoggerFactory.getLogger(classOf[MqttToMqttScenario])
}