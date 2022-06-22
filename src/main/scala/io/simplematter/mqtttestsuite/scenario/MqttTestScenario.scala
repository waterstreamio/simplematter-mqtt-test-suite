package io.simplematter.mqtttestsuite.scenario

import io.netty.handler.codec.mqtt.MqttQoS
import io.simplematter.mqtttestsuite.exception.TestSuiteInitializationException
import io.simplematter.mqtttestsuite.config.{ConnectionMonkeyConfig, MqttBrokerConfig, MqttTestSuiteConfig, ScenarioConfig}
import io.simplematter.mqtttestsuite.model.{ClientId, GroupedTopics, MqttTopicName, NodeIndex}
import io.simplematter.mqtttestsuite.scenario.KafkaToMqttScenario.log
import io.simplematter.mqtttestsuite.scenario.MqttTestScenario.PostScenarioActivity
import io.simplematter.mqtttestsuite.scenario.MqttToKafkaScenario.log
import io.simplematter.mqtttestsuite.scenario.util.{MqttConsumer, MqttPublisher}
import io.simplematter.mqtttestsuite.stats.FlightRecorder
import io.simplematter.mqtttestsuite.util.{ErrorInjector, MessageGenerator}
import org.slf4j.LoggerFactory
import zio.{Fiber, Promise, RIO, Ref, Task, URIO, ZIO, clock}
import zio.clock.Clock
import zio.duration.*

import java.util.concurrent.TimeUnit

trait MqttTestScenario {

  import MqttTestScenario.log

  val name: String

  def start(): RIO[ScenarioEnv, PostScenarioActivity]

  protected val nodeIndex: NodeIndex

  protected val mqttBrokerConfig: MqttBrokerConfig

  protected val scenarioConfig: ScenarioConfig

  protected val thisNodeGroupedTopics: GroupedTopics = MessageGenerator.thisNodeGroupedTopics(scenarioConfig.topicPrefix, scenarioConfig.topicGroupsPerNode, scenarioConfig.topicsPerNode, nodeIndex)

  protected val errorInjector: ErrorInjector


  protected def rampUpPublishers(
                                  scenarioFinalizing: Promise[Nothing, Unit],
                                  rampUpSeconds: Int,
                                  publishingClientsPerNode: Int,
                                  publishingClientPrefix: String,
                                  clientMessagesPerSecond: Double,
                                  qos: MqttQoS,
                                  actionsDuringRampUp: Boolean,
                                  connectionMonkey: ConnectionMonkeyConfig,
                                  expectedRecepients: (MqttTopicName => Option[Iterable[ClientId]])): RIO[ScenarioEnv, Seq[MqttPublisher.WithConnection]] = {
    for {
      startTimestamp <- clock.currentTime(TimeUnit.MILLISECONDS)
      flightRecorder <- ZIO.service[FlightRecorder]
      rampUpComplete <- Promise.make[Nothing, Unit]
      publishingStart = if (actionsDuringRampUp) Task.succeed(()) else rampUpComplete.await
      allPublishersMs = createMqttPublishers(
        publishingClientsPerNode = publishingClientsPerNode,
        publishingClientPrefix = publishingClientPrefix,
        clientMessagesPerSecond = clientMessagesPerSecond,
        qos = qos,
        connectionMonkey = connectionMonkey,
        expectedRecepients = expectedRecepients,
      )
      allPublishers <- ZIO.collectAllPar(allPublishersMs)
      //      _ <- allPublishers.zipWithIndex.foldLeft[RIO[ScenarioEnv, Unit]](RIO[Unit].apply(())) { case (effect, (publisher, publisherIndex)) =>
      //        for {_ <- effect
      //             mcFiber <- publisher.maintainConnection(scenarioFinalizing, mqttBrokerConfig.statusCheckIntervalSeconds.seconds).fork
      //             sendMsgFiber <- publishingStart.zipRight {
      //               log.debug(s"Starting the publishing for ${publisher.clientId}")
      //               publisher.sendMessages()
      //             }.fork
      //            //explicit mcFiber.interrupt needed here in order to stop publishing while other threads keep working (e.g. for receiving the remaining messages)
      //             _ <- (scenarioFinalizing.await *> ZIO { log.debug(s"Scenario is finalizing, stopping publishing for ${publisher.clientId}") } *> ( sendMsgFiber.interrupt.zipPar(mcFiber.interrupt) )).fork
      //             _ <- MqttTestScenario.delayAfterClientSpawn(rampUpSeconds, startTimestamp, publisherIndex, publishingClientsPerNode)
      //             } yield ()
      allPublishersWithConnections <- ZIO.collectAll(allPublishers.zipWithIndex.map { case (publisher, publisherIndex) =>
        for {
          mcFiber <- publisher.maintainConnection(scenarioFinalizing, mqttBrokerConfig.statusCheckIntervalSeconds.seconds).fork
          sendMsgFiber <- publishingStart.zipRight {
            log.debug(s"Starting the publishing for ${publisher.clientId}")
            publisher.sendMessages()
          }.fork
          //explicit mcFiber.interrupt needed here in order to stop publishing while other threads keep working (e.g. for receiving the remaining messages)
          _ <- (scenarioFinalizing.await *> ZIO {
            log.debug(s"Scenario is finalizing, stopping publishing for ${publisher.clientId}")
          } *> (sendMsgFiber.interrupt)).fork
          _ <- MqttTestScenario.delayAfterClientSpawn(rampUpSeconds, startTimestamp, publisherIndex, publishingClientsPerNode)
        } yield (publisher, mcFiber)
      })

      rampUpCompleteTimestamp <- clock.currentTime(TimeUnit.MILLISECONDS)
      _ = log.debug(s"After spawn: ${allPublishers.size} publishers, publishers ramp up actual duration = ${rampUpCompleteTimestamp - startTimestamp}")
      _ <- rampUpComplete.succeed(())
    } yield allPublishersWithConnections
  }

  private def createMqttPublishers(publishingClientsPerNode: Int,
                                   publishingClientPrefix: String,
                                   clientMessagesPerSecond: Double,
                                   qos: MqttQoS,
                                   connectionMonkey: ConnectionMonkeyConfig,
                                   expectedRecepients: (MqttTopicName => Option[Iterable[ClientId]])
                                  ): Seq[URIO[ScenarioEnv, MqttPublisher]] = {
    val intermittentThreshold = connectionMonkey.intermittentClientsPercentage * publishingClientsPerNode / 100;
    (0 until publishingClientsPerNode).map { i =>
      for {
        flightRecorder <- ZIO.service[FlightRecorder]
        clientId = ClientId(publishingClientPrefix + i)
        _ = log.debug(s"Creating a publisher ${clientId}")
        p <- MqttPublisher.make(mqttBrokerConfig = mqttBrokerConfig,
          connectionMonkey = connectionMonkey,
          clientId = clientId,
          intermittent = i < intermittentThreshold,
          cleanSession = !mqttBrokerConfig.persistentSession,
          messagesPerSecond = clientMessagesPerSecond,
          messageMinSize = scenarioConfig.messageMinSize,
          messageMaxSize = scenarioConfig.messageMaxSize,
          qos = qos,
          topics = thisNodeGroupedTopics,
          expectedRecepients = expectedRecepients,
          errorInjector = errorInjector
        )
      } yield p
    }
  }


  protected def rampUpMqttConsumers(rampUpSeconds: Int,
                                    subscribingClientPrefix: String,
                                    subscribingClientsPerNode: Int,
                                    subscribeTopicGroupsPerClient: Int,
                                    subscribeTopicsPerClient: Int,
                                    subscribeWildcardMessageDeduplicate: Boolean,
                                    qos: MqttQoS,
                                    connectionMonkey: ConnectionMonkeyConfig,
                                    scenarioFinalizing: Promise[Nothing, Unit]
                                   ): RIO[ScenarioEnv, (Seq[MqttConsumer.WithConnection], Map[MqttTopicName, Seq[ClientId]])] = {
    for {
      startTimestamp <- clock.currentTime(TimeUnit.MILLISECONDS)
      (allConsumersMs, clientsByTopics) = createMqttConsumers(
        consumingClientPrefix = subscribingClientPrefix,
        subscribingClientsPerNode = subscribingClientsPerNode,
        subscribeTopicGroupsPerClient = subscribeTopicGroupsPerClient,
        subscribeTopicsPerClient = subscribeTopicsPerClient,
        subscribeWildcardMessageDeduplicate = subscribeWildcardMessageDeduplicate,
        qos = qos,
        connectionMonkey = connectionMonkey
      )

      //      rampUpComplete <- Promise.make[Nothing, Unit]
      allConsumers <- ZIO.collectAllPar(allConsumersMs)
      consumersWithConnectionFibers <- allConsumers.zipWithIndex.foldLeft[RIO[ScenarioEnv, Seq[MqttConsumer.WithConnection]]](RIO[Seq[MqttConsumer.WithConnection]] {
        Seq.empty
      }) { case (accEffect, (consumer, consumerIndex)) =>
        for {acc <- accEffect
             mcFiber <- consumer.maintainConnection(scenarioFinalizing, mqttBrokerConfig.statusCheckIntervalSeconds.seconds).fork
             _ <- MqttTestScenario.delayAfterClientSpawn(rampUpSeconds, startTimestamp, consumerIndex, subscribingClientsPerNode)
             } yield acc :+ (consumer, mcFiber)
      }
      _ = log.debug("Creating consumers complete, waiting for subscriptions")
      _ <- ZIO.collectAllPar(allConsumers.map(_.waitForSubscribe(mqttBrokerConfig.subscribeTimeoutSeconds.seconds).retryN(mqttBrokerConfig.subscribeMaxRetries)))
      _ = log.debug("Consumer subscriptions established")
      stopTimestamp <- clock.currentTime(TimeUnit.MILLISECONDS)
      _ = log.debug(s"After ramp up: ${allConsumers.size} consumers, consumers ramp up actual duration = ${stopTimestamp - startTimestamp}")
      //      _ <- rampUpComplete.succeed(())
    } yield (consumersWithConnectionFibers, clientsByTopics)
  }


  private def createMqttConsumers(consumingClientPrefix: String,
                                  subscribingClientsPerNode: Int,
                                  subscribeTopicGroupsPerClient: Int,
                                  subscribeTopicsPerClient: Int,
                                  subscribeWildcardMessageDeduplicate: Boolean,
                                  qos: MqttQoS,
                                  connectionMonkey: ConnectionMonkeyConfig): (Seq[URIO[ScenarioEnv, MqttConsumer]], Map[MqttTopicName, Seq[ClientId]]) = {
    val intermittentThreshold = connectionMonkey.intermittentClientsPercentage * subscribingClientsPerNode / 100;
    val consumersWithTopics = (0 until subscribingClientsPerNode).map { i =>
      val clientId = ClientId(consumingClientPrefix + i)
      val topicsSlice = thisNodeGroupedTopics.circularSlice(subscribeTopicGroupsPerClient, subscribeTopicsPerClient, i)
      log.debug("Creating MQTT consumer {} for patterns {}", clientId, topicsSlice.patterns)
      val consumerM = MqttConsumer.make(mqttBrokerConfig = mqttBrokerConfig,
        connectionMonkey = connectionMonkey,
        clientId = clientId,
        intermittent = i < intermittentThreshold,
        cleanSession = !mqttBrokerConfig.persistentSession,
        qos = qos,
        topicPatterns = topicsSlice.patterns,
        errorInjector = errorInjector
      )
      val topics = topicsSlice.assignedTopics ++ topicsSlice.topicsForGroups
      val deduplicatedTopics = if (subscribeWildcardMessageDeduplicate) topics.toSet.toSeq else topics
      (clientId, consumerM, deduplicatedTopics)
    }
    val clientsByTopic = consumersWithTopics.flatMap { (clientId, _, topics) =>
      topics.map {
        _ -> clientId
      }
    }.groupMap(_._1)(_._2)
    val consumersM = consumersWithTopics.map(_._2)
    log.debug("Clients by topic: {}", clientsByTopic)
    (consumersM, clientsByTopic)
  }
}

object MqttTestScenario {
  private val log = LoggerFactory.getLogger(classOf[MqttTestScenario])

  def create(config: MqttTestSuiteConfig, scenarioConfig: ScenarioConfig, runnerNodeIndex: NodeIndex): MqttTestScenario = {
    val errorInjector = ErrorInjector(config.errorInjection)

    scenarioConfig match {
      case sc: ScenarioConfig.MqttPublishOnly => new MqttPublishOnlyScenario(config.stepInterval,
        config.nodeIdNonEmpty,
        runnerNodeIndex,
        config.mqtt,
        sc,
        errorInjector)
      case sc: ScenarioConfig.MqttToKafka => new MqttToKafkaScenario(config.stepInterval,
        config.nodeIdNonEmpty,
        runnerNodeIndex,
        config.mqtt,
        config.kafka,
        sc,
        errorInjector)
      case sc: ScenarioConfig.KafkaToMqtt => new KafkaToMqttScenario(config.stepInterval,
        config.nodeIdNonEmpty,
        runnerNodeIndex,
        config.mqtt,
        config.kafka,
        sc,
        errorInjector)
      case sc: ScenarioConfig.MqttToMqtt => new MqttToMqttScenario(config.stepInterval,
        config.nodeIdNonEmpty,
        runnerNodeIndex,
        config.mqtt,
        sc,
        errorInjector)
    }
  }

  def delayAfterClientSpawn(rampUpSeconds: Int, startTimestamp: Long, clientsNumber: Int, targetClientsNumber: Int): RIO[Clock, Unit] = {
    for {
      now <- clock.currentTime(TimeUnit.MILLISECONDS)
      n = clientsNumber
      tNext = rampUpSeconds * 1000L * n / targetClientsNumber + startTimestamp
      delay = (if (n >= targetClientsNumber || tNext < now) 0 else tNext - now).milliseconds
      _ = log.debug("Sleeping for {}, current clients {}", delay, n)
      _ <- clock.sleep(delay)
    } yield ()
  }

  type PostScenarioActivity = Fiber[Any, Any]
}