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
import zio.{Clock, Duration, Executor, Fiber, IO, Promise, RIO, Ref, Runtime, Schedule, Scope, Task, UIO, URIO, ZIO, ZLayer}
import zio.stream.{ZSink, ZStream}

import java.util.concurrent.{ConcurrentHashMap, LinkedBlockingQueue, ThreadPoolExecutor, TimeUnit}
import scala.concurrent.Future
import zio.kafka.serde.Serde
import zio.kafka.consumer.{Consumer, ConsumerSettings, Subscription}
import Consumer.OffsetRetrieval
import org.apache.kafka.common.TopicPartition

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
      startTime <- Clock.currentTime(TimeUnit.MILLISECONDS)
      _ <- Clock.sleep(Duration.fromSeconds(scenarioConfig.durationSeconds))
      _ <- finalizingScenario.succeed(())
      //TODO consider waiting here until publishing actually completes. Currently, StatsStorage.waitCompletion makes sure that the transmission completes before final stats.
      _ <- flightRecorder.scenarioDone()
      stopTime <- Clock.currentTime(TimeUnit.MILLISECONDS)
      _ = log.info(s"Stopped scenario after ${(stopTime - startTime)/1000} s")
      connectionsFiber = Fiber.collectAll(publishersWithConnections.map { case (_, mcFiber) => mcFiber})
      _ <- connectionsFiber.await.map { res =>
        val ifmClients = publishersWithConnections.map(_._1).map(client => (client.clientId, client.publishInFlightCount())).filter(_._2 > 0)
        log.debug("Connections interrupted. Clients with in-flight messages: {}", ifmClients)
      }.fork
//     } yield ( connectionsFiber.zip(kafkaFiber) )
    } yield ( connectionsFiber )
}

  /**
   *
   * @return effect that completes when Kafka client is fully initlialized. Actual processing is forked and runs in the background.
   */
//  private def subscribeKafkaWithFixedPartitions(): RIO[ScenarioEnv, Fiber[Any, Any]] = {
  private def subscribeKafkaWithFixedPartitions(): RIO[ScenarioEnv, Unit] = {
    val offsetsCache = KafkaOffsetsCache(kafkaConfig, "mqtt2kafka")

    //To make sure that reading doesn't compete for the resources with the publishing we need a separate executor.
    //Dedicated layer for the executor so that it would be spawned before KafkaConsumer and shut down after.
    val executorLayer = ZLayer.scoped {
      ZIO.acquireRelease {
        ZIO.attempt { ThreadPoolExecutor(2, 10, 10, TimeUnit.SECONDS, new LinkedBlockingQueue[Runnable]()) }
      } {
        tpe => ZIO.attempt { tpe.shutdown() }.ignoreLogged
      }
    }
    val consumerLayer = ZLayer.scoped(for {
      hz <- ZIO.service[HazelcastInstance]
      consumerSettings: ConsumerSettings =
        ConsumerSettings(kafkaConfig.bootstrapServersSeq.toList)
          .withProperties(kafkaConfig.consumerProperties)
          .withPollInterval(Duration.fromMillis(0)) /* iterate as fast as possible, relying on pollTimeout for sleeping */
          .withPollTimeout(kafkaConfig.pollTimeout)
          .withOffsetRetrieval(OffsetRetrieval.Manual { topicsPartitions => offsetsCache.getOffsets(topicsPartitions) })
      kafkaConsumer <- Consumer.make(consumerSettings)
    } yield kafkaConsumer)

    def streamExec(thisNodeTopicPartitions: Set[TopicPartition]): RIO[Consumer with Clock with FlightRecorder with HazelcastInstance with ThreadPoolExecutor, Unit] = for {
      flightRecorder <- ZIO.service[FlightRecorder]
      tpExecutor <- ZIO.service[ThreadPoolExecutor]
      kafkaReadingExecutor = zio.Executor.fromThreadPoolExecutor(tpExecutor)
      _ <- Consumer.subscribeAnd(Subscription.Manual(thisNodeTopicPartitions))
        .plainStream(Serde.string, Serde.string)
        .mapZIO { rec =>
          ((for {
            now <- Clock.currentTime(TimeUnit.MILLISECONDS)
            receivingTimestamp = if (scenarioConfig.useKafkaTimestampForLatency) rec.timestamp else now
            msgPrefix <- MessageGenerator.unpackMessagePrefix(rec.value)
            (messageId, sendingTimestamp) = msgPrefix
            _ <- flightRecorder.messageReceived(messageId, rec.record.topic() + " " + rec.key, kafkaClientId, sendingTimestamp, receivingTimestamp)
            _ = if(log.isDebugEnabled())
              log.debug("Received Kafka message: id={}, topic={}, partition={}, key={}, endToEndLatency={}, mqttToKafkaLatency={}", messageId, rec.record.topic(), rec.record.partition(), rec.key, receivingTimestamp-sendingTimestamp, receivingTimestamp - rec.timestamp)
              else ()
          } yield rec.offset).catchAll { err =>
            ZIO.succeed {
              log.error("Failed to process Kafka record", err)
              rec.offset
            }
          })
        }.runDrain.onExecutor(kafkaReadingExecutor)
      _ = log.info("***** kafka stream exec done")
    } yield ()

      for {
        //TODO configurable timeout
        thisNodeTopicPartitions <- KafkaUtils.getThisNodePartitions(nodeIndex, kafkaConfig, scenarioConfig.kafkaTopicsSeq, 30).map(_.toSet)
        //Init offsets cache before starting the message reading loop
        _ <- offsetsCache.getOffsets(thisNodeTopicPartitions)
        /* We can only fork here - it doesn't start consuming if forked before provideSomeLayer */
        kafkaFiber: Fiber[Any, Any] <- streamExec(thisNodeTopicPartitions).provideSome[ScenarioEnv](executorLayer >+> consumerLayer).fork
      } yield ()
  }
}

object MqttToKafkaScenario {
  val name: String = "mqttToKafka"

  private val log = LoggerFactory.getLogger(classOf[MqttToKafkaScenario])
}