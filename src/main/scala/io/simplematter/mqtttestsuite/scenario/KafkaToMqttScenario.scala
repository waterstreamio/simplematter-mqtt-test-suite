package io.simplematter.mqtttestsuite.scenario

import io.netty.handler.codec.mqtt.MqttQoS
import io.simplematter.mqtttestsuite.config.{KafkaConfig, MqttBrokerConfig, ScenarioConfig}
import io.simplematter.mqtttestsuite.kafka.KafkaUtils
import io.simplematter.mqtttestsuite.model.{ClientId, GroupedTopics, MessageId, MqttTopicName, NodeId, NodeIndex}
import io.simplematter.mqtttestsuite.scenario.util.{MqttConsumer, MqttPublisher}
import io.simplematter.mqtttestsuite.stats.FlightRecorder
import io.simplematter.mqtttestsuite.util.{ErrorInjector, MessageGenerator, pickCircular, scheduleFrequency}
import org.apache.kafka.clients.producer.ProducerConfig
import org.slf4j.LoggerFactory
import zio.{Clock, Duration, Fiber, Promise, RIO, Ref, Schedule, Scope, Semaphore, Task, URIO, ZIO, ZLayer}
import zio.kafka.producer.*
import zio.kafka.serde.Serde

import java.util.concurrent.TimeUnit
import scala.util.Random

class KafkaToMqttScenario(stepInterval: Duration,
                          nodeId: NodeId,
                          protected val nodeIndex: NodeIndex,
                          protected val mqttBrokerConfig: MqttBrokerConfig,
                          kafkaConfig: KafkaConfig,
                          protected val scenarioConfig: ScenarioConfig.KafkaToMqtt,
                          protected val errorInjector: ErrorInjector
                         ) extends MqttTestScenario {
  import KafkaToMqttScenario._

  override val name = KafkaToMqttScenario.name

  private val consumingClientPrefix = scenarioConfig.clientPrefix + nodeId + "-"

  private val kafkaClientId = ClientId("kafka-"+nodeId)

  override def start(): RIO[ScenarioEnv, MqttTestScenario.PostScenarioActivity] = {

    ZIO.scoped {
      for {
        _ <- ZIO.succeed {
          log.info(s"Starting the scenario for node ${nodeId}")
        }
        scenarioStop <- Promise.make[Nothing, Unit]
        flightRecorder <- ZIO.service[FlightRecorder]
        scenarioFinalizing <- Promise.make[Nothing, Unit]
        _ <- flightRecorder.scenarioRampUpStarted(name, scenarioConfig.rampUpSeconds, scenarioConfig.durationSeconds)
        _ <- flightRecorder.mqttConnectionsExpected(scenarioConfig.subscribingClientsPerNode)
        rampUpResult <- rampUpMqttConsumers(
          rampUpSeconds = scenarioConfig.rampUpSeconds,
          subscribingClientPrefix = consumingClientPrefix,
          subscribingClientsPerNode = scenarioConfig.subscribingClientsPerNode,
          subscribeTopicGroupsPerClient = scenarioConfig.subscribeTopicGroupsPerClient,
          subscribeTopicsPerClient = scenarioConfig.subscribeTopicsPerClient,
          subscribeWildcardMessageDeduplicate = scenarioConfig.subscribeWildcardMessageDeduplicate,
          qos = MqttQoS.valueOf(scenarioConfig.qos),
          connectionMonkey = scenarioConfig.connectionMonkey,
          scenarioFinalizing = scenarioFinalizing
        )
        (clientsWithConnections, mqttTopicToClients) = rampUpResult
        _ <- flightRecorder.scenarioRunning()
        startTime <- Clock.currentTime(TimeUnit.MILLISECONDS)
        producingFiber <- startProducing(flightRecorder, mqttTopicToClients).fork
        _ <- Clock.sleep(Duration.fromSeconds(scenarioConfig.durationSeconds))
        _ <- scenarioStop.succeed(())
        _ <- producingFiber.interrupt
        _ <- flightRecorder.scenarioDone()
        stopTime <- Clock.currentTime(TimeUnit.MILLISECONDS)
        _ = log.info(s"Stopping scenario after ${(stopTime - startTime) / 1000} s")
        _ <- scenarioFinalizing.succeed(())
        connectionsFiber = Fiber.collectAll(clientsWithConnections.map { case (_, mcFiber) => mcFiber })
        //MQTT consumers keep working in order to catch up with the possible left-overs
      } yield connectionsFiber
    }
  }

  private def startProducing(flightRecorder: FlightRecorder, mqttTopicSubscribers: Map[MqttTopicName, Seq[ClientId]]): RIO[ScenarioEnv with Scope, Unit] = {
    val producerSettings = ProducerSettings(kafkaConfig.bootstrapServersSeq.toList)
      .withProperties(kafkaConfig.producerProperties)

    val pseudoClientId = ClientId(nodeId.value + "-kafka")
//    ZIO.scoped {(
        for {
        producer <- Producer.make(producerSettings)
        sendingsSemaphore <- Semaphore.make(permits = kafkaConfig.maxParallelProduce)
        msgCounter <- Ref.make[Int](0)
//        _ <- (for {
        sendMessageM = (for {
          n <- msgCounter.updateAndGet(_ + 1)
          msgId = MessageId(pseudoClientId, n)
//          now <- Clock.currentTime(TimeUnit.MILLISECONDS)
          now <- Clock.currentTime(TimeUnit.MILLISECONDS)
          mqttTopic = thisNodeGroupedTopics.randomTopic()
          messageBody = MessageGenerator.generatePackedMessage(msgId, now, scenarioConfig.messageMinSize, scenarioConfig.messageMaxSize)
          expectedRecepients = mqttTopicSubscribers.getOrElse(mqttTopic, Seq.empty)
          //          _ = log.trace("Publishing {} message {}, timestamp {}, fan-out {}", nodeId, msgId, now, expectedRecepients)
          /* not interrupt to make sure that the statistics gets written correctly when the test shuts down */
          _ <- //sendingsSemaphore.withPermit(
            flightRecorder.
            recordMessageSend(msgId, producer.produce(scenarioConfig.kafkaDefaultTopic, mqttTopic.value, messageBody, Serde.string, Serde.string), mqttTopic, Some(expectedRecepients)).
            uninterruptible//. //).
//            catchAll { err => ZIO.attempt { log.warn("Failed to send Kafka message", err) } }.
//            fork
//        } yield ()).repeat(scheduleFrequency(scenarioConfig.kafkaProducerMessagesPerSecond))
        } yield ())
          _ <- (sendingsSemaphore.withPermit(sendMessageM).fork).repeat(scheduleFrequency(scenarioConfig.kafkaProducerMessagesPerSecond))
//        _ <- (sendMessageM.fork).repeat(scheduleFrequency(scenarioConfig.kafkaProducerMessagesPerSecond))

          //          .onInterrupt({ ZIO.attempt { log.warn("***** Kafka sending loop interrupted") }.ignoreLogged })
//          _ = log.info("***** Kafka sending loop complete")
      } yield ()
//    )}
  }
}

object KafkaToMqttScenario {
  val name: String = "kafkaToMqtt"

  private val log = LoggerFactory.getLogger(classOf[KafkaToMqttScenario])
}