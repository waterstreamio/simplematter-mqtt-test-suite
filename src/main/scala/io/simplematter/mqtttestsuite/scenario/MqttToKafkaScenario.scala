package io.simplematter.mqtttestsuite.scenario

import com.hazelcast.core.HazelcastInstance
import io.netty.handler.codec.mqtt.MqttQoS
import io.simplematter.mqtttestsuite.config.{KafkaConfig, MqttBrokerConfig, ScenarioConfig}
import io.simplematter.mqtttestsuite.kafka.{KafkaOffsetsCache, KafkaUtils}
import io.simplematter.mqtttestsuite.scenario.util.MqttPublisher
import io.simplematter.mqtttestsuite.stats.FlightRecorder
import io.simplematter.mqtttestsuite.util.{ErrorInjector, MessageGenerator}
import io.simplematter.mqtttestsuite.model.{ClientId, GroupedTopics, MqttTopicName, NodeId, NodeIndex}
import org.slf4j.LoggerFactory
import zio.{Fiber, Has, IO, Promise, RIO, Ref, Runtime, Schedule, Task, UIO, URIO, ZIO, ZLayer, ZManaged, clock}
import zio.stream.{ZSink, ZStream}
import zio.duration.*
import zio.clock.Clock
import zio.blocking.Blocking

import java.util.concurrent.{ConcurrentHashMap, LinkedBlockingQueue, ThreadPoolExecutor, TimeUnit}
import scala.concurrent.Future
import zio.kafka.serde.Serde
import zio.kafka.consumer.{Consumer, ConsumerSettings, Subscription}
import Consumer.OffsetRetrieval
import org.apache.kafka.common.TopicPartition
import zio.internal.Executor

import java.nio.file.Path
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import scala.jdk.CollectionConverters.*

/**
 * Publishes messages to MQTT, expects them appear from Kafka (not implemented yet)
 *
 * @param config
 */
class MqttToKafkaScenario(stepInterval: Duration,
                          nodeId: NodeId,
                          protected val nodeIndex: NodeIndex,
                          protected val mqttBrokerConfig: MqttBrokerConfig,
                          kafkaConfig: KafkaConfig,
                          protected val scenarioConfig: ScenarioConfig.MqttToKafka,
                          protected val errorInjector: ErrorInjector
                         ) extends MqttTestScenario {
  import MqttToKafkaScenario._

  override val name: String = MqttToKafkaScenario.name

  private val publishingClientPrefix = scenarioConfig.clientPrefix + nodeId + "-"

  private val kafkaClientId = ClientId("kafka-"+nodeId)

  //To make sure that reading doesn't compete for the resources with the publishing
  private val kafkaReadingExecutor = Executor.fromThreadPoolExecutor(_ => 1024)(new ThreadPoolExecutor(
    2, 10, 10, TimeUnit.SECONDS, new LinkedBlockingQueue[Runnable]()
  ))

  override def start(): RIO[ScenarioEnv, Fiber[Any, Any]] = {
    log.info(s"Starting the scenario for node ${nodeId}")

    val noneRecepients = (_ => None): (MqttTopicName => Option[Iterable[ClientId]])

      for {
      flightRecorder <- ZIO.service[FlightRecorder]
      finalizingScenario <- Promise.make[Nothing, Unit]
      _ <- flightRecorder.scenarioRampUpStarted(name, scenarioConfig.rampUpSeconds, scenarioConfig.durationSeconds)
      _ <- flightRecorder.mqttConnectionsExpected(scenarioConfig.publishingClientsPerNode)
      _ <- subscribeKafkaWithFixedPartitions()
      publishersWithConnections <- rampUpPublishers(//scenarioStop.await,
        scenarioFinalizing = finalizingScenario,
        rampUpSeconds = scenarioConfig.rampUpSeconds,
        publishingClientsPerNode = scenarioConfig.publishingClientsPerNode,
        publishingClientPrefix = publishingClientPrefix,
        clientMessagesPerSecond = scenarioConfig.clientMessagesPerSecond,
        qos = MqttQoS.valueOf(scenarioConfig.qos),
        connectionMonkey = scenarioConfig.connectionMonkey,
        actionsDuringRampUp = scenarioConfig.actionsDuringRampUp,
        expectedRecepients = noneRecepients
      )
      _ <- flightRecorder.scenarioRunning()
      startTime <- clock.currentTime(TimeUnit.MILLISECONDS)
      _ <- clock.sleep(scenarioConfig.durationSeconds.seconds)
      _ <- finalizingScenario.succeed(())
      //TODO consider waiting here until publishing actually completes. Currently, StatsStorage.waitCompletion makes sure that the transmission completes before final stats.
      _ <- flightRecorder.scenarioDone()
      stopTime <- clock.currentTime(TimeUnit.MILLISECONDS)
      _ = log.info(s"Stopped scenario after ${(stopTime - startTime)/1000} s")
      connectionsFiber = Fiber.collectAll(publishersWithConnections.map { case (_, mcFiber) => mcFiber})
      _ <- connectionsFiber.await.map { res =>
        val ifmClients = publishersWithConnections.map(_._1).map(client => (client.clientId, client.publishInFlightCount())).filter(_._2 > 0)
        log.debug("Connections interrupted. Clients with in-flight messages: {}", ifmClients)
      }.fork
     } yield ( connectionsFiber )
  }

  /**
   *
   * @return effect that completes when Kafka client is fully initlialized. Actual processing is forked and runs in the background.
   */
  private def subscribeKafkaWithFixedPartitions(): RIO[ScenarioEnv, Unit] = {
    val offsetsCache = KafkaOffsetsCache(kafkaConfig, "mqtt2kafka")

    val consumerLayer = ZLayer.fromManaged(for {
      hz <- ZManaged.service[HazelcastInstance]
      blocking <- ZManaged.service[Blocking.Service]
      consumerSettings: ConsumerSettings =
        ConsumerSettings(kafkaConfig.bootstrapServersSeq.toList)
          .withProperties(kafkaConfig.consumerProperties)
          .withPollInterval(0.milliseconds) /* iterate as fast as possible, relying on pollTimeout for sleeping */
          .withPollTimeout(kafkaConfig.pollTimeout)
          .withOffsetRetrieval(OffsetRetrieval.Manual { topicsPartitions => offsetsCache.getOffsets(topicsPartitions).provide(Has(blocking)) })
      kafkaConsumer <- Consumer.make(consumerSettings)
    } yield kafkaConsumer)

    def streamExec(thisNodeTopicPartitions: Set[TopicPartition]): RIO[Has[Consumer] with Clock with Blocking with Has[FlightRecorder] with Has[HazelcastInstance], Unit] = for {
      flightRecorder <- ZIO.service[FlightRecorder]

      _ <- Consumer.subscribeAnd(Subscription.Manual(thisNodeTopicPartitions))
        .plainStream(Serde.string, Serde.string)
        .mapM { rec =>
          ((for {
            now <- clock.currentTime(TimeUnit.MILLISECONDS)
            receivingTimestamp = if (scenarioConfig.useKafkaTimestampForLatency) rec.timestamp else now
            (messageId, sendingTimestamp) <- MessageGenerator.unpackMessagePrefix(rec.value)
            _ <- flightRecorder.messageReceived(messageId, rec.record.topic() + " " + rec.key, kafkaClientId, sendingTimestamp, receivingTimestamp)
            _ = if(log.isDebugEnabled()) log.debug("Received Kafka message: id={}, topic={}, partition={}, key={}", messageId, rec.record.topic(), rec.record.partition(), rec.key) else ()
          } yield rec.offset).catchAll { err =>
            ZIO.succeed {
              log.error("Failed to process Kafka record", err)
              rec.offset
            }
          })
        }.runDrain
    } yield ()

    for {
      //TODO configurable timeout
      thisNodeTopicPartitions <- KafkaUtils.getThisNodePartitions(nodeIndex, kafkaConfig, scenarioConfig.kafkaTopicsSeq, 30).map(_.toSet)
      //Init offsets cache before starting the message reading loop
      _ <- offsetsCache.getOffsets(thisNodeTopicPartitions)
      /* We can only fork here due to strange zio-kafka behavior - it doesn't start consuming if forked before provideSomeLayer */
      _ <- streamExec(thisNodeTopicPartitions).provideSomeLayer[ScenarioEnv](consumerLayer).lock(kafkaReadingExecutor).fork
    } yield ()
  }
}

object MqttToKafkaScenario {
  val name: String = "mqttToKafka"

  private val log = LoggerFactory.getLogger(classOf[MqttToKafkaScenario])
}