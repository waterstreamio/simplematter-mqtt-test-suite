package io.simplematter.mqtttestsuite.integration

import io.simplematter.mqtttestsuite.config.{HazelcastConfig, KafkaConfig, MqttBrokerConfig, MqttTestSuiteConfig, ScenarioConfig, StatsConfig}
import io.simplematter.mqtttestsuite.hazelcast.HazelcastUtil
import io.simplematter.mqtttestsuite.model.NodeIndex
import io.simplematter.mqtttestsuite.mqtt.{MqttClient, MqttClientSpec}
import io.simplematter.mqtttestsuite.scenario.{MqttTestScenario, ScenarioState}
import io.simplematter.mqtttestsuite.stats.model.StatsSummary
import io.simplematter.mqtttestsuite.stats.{StatsReporter, StatsStorage}
import io.simplematter.mqtttestsuite.testutil.ContainerUtils
import org.scalatest.{BeforeAndAfterAll, OptionValues}
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should
import org.scalatest.time.{Millis, Seconds, Span}
import org.slf4j.LoggerFactory
import org.testcontainers.containers.GenericContainer

import zio.duration.*
import zio.{ZIO, ZLayer, clock}
import zio.clock.Clock
import zio.blocking.Blocking

class MqttToMqttScenarioSpec extends AnyFlatSpec
  with should.Matchers
  with ScalaFutures
  with OptionValues
  with BeforeAndAfterAll
  with Eventually {

  private val log = LoggerFactory.getLogger(classOf[MqttToMqttScenarioSpec])

  implicit val defaultPatience: PatienceConfig = PatienceConfig(timeout =  Span(5, Seconds), interval = Span(5, Millis))

  private val enableTestcontainers = true
//    private val enableTestcontainers = false
  private lazy val mqttPort: Int = if(enableTestcontainers) mosquittoContainer.getMappedPort(1883) else 1883


  private lazy val mosquittoContainer: GenericContainer[_] = ContainerUtils.mosquittoContainer()

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    if(enableTestcontainers) {
      log.debug("Starting MQTT broker")
      mosquittoContainer.start()
    }
  }

  override protected def afterAll(): Unit = {
    if(enableTestcontainers) {
      log.debug("Stopping MQTT broker")
      mosquittoContainer.stop()
    }
    super.afterAll()
  }

  private lazy val testSuiteConfig = MqttTestSuiteConfig(
    stepIntervalMillis = 1000,
    completionTimeoutMillis = 1000,
    nodeId = None,
    stats = StatsConfig(statsPort = None),
    hazelcast = HazelcastConfig(),
    mqtt = MqttBrokerConfig(server = s"localhost:${mqttPort}"),
    kafka = KafkaConfig(bootstrapServers = "fake"),

    expectedRunnerNodesCount = 1,
    expectedAggregatorNodesCount = 0
  )

  val scenarioConfig = ScenarioConfig.MqttToMqtt(
      rampUpSeconds = 1,
      actionsDuringRampUp = false,
      durationSeconds = 5,
      topicsPerNode = 10,
      topicGroupsPerNode = 2,
      publishingClientsPerNode = 5,
      publishingClientMessagesPerSecond = 1,
      publishQos = 0,
      subscribingClientsPerNode = 5,
      subscribeQos = 0,
      subscribeTopicsPerClient = 4 /*fan-out=2*/ ,
      subscribeTopicGroupsPerClient = 0,
      subscribeWildcardMessageDeduplicate = true)

  "MqttToMqttScenario" should "run successfully" in {
    val hz = HazelcastUtil.hazelcastInstanceLayer(testSuiteConfig.hazelcast)

    val statsStorage = (hz ++ ZLayer.requires[Clock] ++ ZLayer.requires[Blocking]) >>>
      StatsStorage.layer(testSuiteConfig.nodeIdNonEmpty,
        testSuiteConfig.stats,
        testSuiteConfig.stats.statsUploadInterval,
        testSuiteConfig.mqtt,
        scenarioConfig).passthrough

    val endStatsSummary: StatsSummary = zio.Runtime.default.unsafeRun {
      (for {
        statsStorage <- ZIO.service[StatsStorage]
        runnerNodeIndex = NodeIndex(0, 1)
        scn = MqttTestScenario.create(testSuiteConfig, scenarioConfig, runnerNodeIndex)
        //race to make sure the scenario doesn't hang forever
        _ <- scn.start().race(clock.sleep((2*scenarioConfig.durationSeconds).seconds))
        _ <- StatsStorage.waitCompletion(testSuiteConfig.completionTimeout)
        _ <- StatsStorage.finalizeStats()
      } yield statsStorage.getStats()).provideSomeLayer(statsStorage)
    }

    endStatsSummary.scenario.scenarioName shouldBe "mqttToMqtt"
    endStatsSummary.scenario.scenarioState shouldBe ScenarioState.Done
    endStatsSummary.scenario.nodes shouldBe 1

    endStatsSummary.mqttConnectAttempts shouldBe (scenarioConfig.publishingClientsPerNode + scenarioConfig.subscribingClientsPerNode)
    endStatsSummary.mqttConnectSuccess shouldBe endStatsSummary.mqttConnectAttempts

    endStatsSummary.mqttSubscribePatternsSuccess shouldBe scenarioConfig.subscribingClientsPerNode*(scenarioConfig.subscribeTopicsPerClient + scenarioConfig.subscribeTopicGroupsPerClient)
    endStatsSummary.sent.success should be >= ((scenarioConfig.durationSeconds - 1)*scenarioConfig.publishingClientsPerNode*scenarioConfig.publishingClientMessagesPerSecond).toInt
    endStatsSummary.sent.success should be <= ((scenarioConfig.durationSeconds + 1)*scenarioConfig.publishingClientsPerNode*scenarioConfig.publishingClientMessagesPerSecond).toInt
    endStatsSummary.received.expectedCount shouldBe endStatsSummary.sent.success * 2 /*fan-out = 2*/
    endStatsSummary.received.count shouldBe endStatsSummary.received.expectedCount
  }
}
