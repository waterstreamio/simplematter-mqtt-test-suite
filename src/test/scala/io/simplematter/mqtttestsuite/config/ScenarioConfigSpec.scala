package io.simplematter.mqtttestsuite.config

import org.scalacheck.Gen
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import zio.json.*

class ScenarioConfigSpec extends AnyFlatSpec
  with should.Matchers
  with ScalaCheckPropertyChecks
  with EitherValues {

  private val mqttPublishOnlyGen = for {
    rampUpSeconds <- Gen.chooseNum(0, 1000)
    actionsDuringRampUp <- Gen.oneOf(false, true)
    durationSeconds <- Gen.chooseNum(0, 10000)
    topicsNumber <- Gen.chooseNum(0, 1000000)
    topicsGroupsNumber <- Gen.chooseNum(0, 100000)
    publishingClientsNumber <- Gen.chooseNum(0, 1000000)
    clientMessagesPerSecond <- Gen.chooseNum(0.0, 100.0)
    topicPrefix <- Gen.asciiPrintableStr
    clientPrefix <- Gen.asciiPrintableStr
    randomizeClientPrefix <- Gen.oneOf(false, true)
    qos <- Gen.chooseNum(0, 2)
    messageMinSize <- Gen.chooseNum(0, 10000)
    messageMaxSize <- Gen.chooseNum(0, 10000)
  } yield ScenarioConfig.MqttPublishOnly(
    rampUpSeconds = rampUpSeconds,
    actionsDuringRampUp = actionsDuringRampUp,
    durationSeconds = durationSeconds,
    topicsPerNode = topicsNumber,
    topicGroupsPerNode = topicsGroupsNumber,
    publishingClientsNumber = publishingClientsNumber,
    clientMessagesPerSecond = clientMessagesPerSecond,
    topicPrefix = topicPrefix,
    clientPrefix = clientPrefix,
    randomizeClientPrefix = randomizeClientPrefix,
    qos = qos,
    messageMinSize = messageMinSize,
    messageMaxSize = messageMaxSize)

  private val mqttToMqttGen = for {
    rampUpSeconds <- Gen.chooseNum(0, 1000)
    actionsDuringRampUp <- Gen.oneOf(false, true)
    durationSeconds <- Gen.chooseNum(0, 10000)
    topicsNumber <- Gen.chooseNum(0, 1000000)
    topicsGroupsNumber <- Gen.chooseNum(0, 100000)
    publishingClientsNumber <- Gen.chooseNum(0, 1000000)
    clientMessagesPerSecond <- Gen.chooseNum(0.0, 100.0)
    topicPrefix <- Gen.asciiPrintableStr
    pubClientPrefix <- Gen.asciiPrintableStr
    subClientPrefix <- Gen.asciiPrintableStr
    randomizeClientPrefix <- Gen.oneOf(false, true)
    qos <- Gen.chooseNum(0, 2)
    messageMinSize <- Gen.chooseNum(0, 10000)
    messageMaxSize <- Gen.chooseNum(0, 10000)
  } yield ScenarioConfig.MqttToMqtt(
    rampUpSeconds = rampUpSeconds,
    actionsDuringRampUp = actionsDuringRampUp,
    durationSeconds = durationSeconds,
    topicsPerNode = topicsNumber,
    topicGroupsPerNode = topicsGroupsNumber,
    publishingClientsPerNode = publishingClientsNumber,
    publishingClientMessagesPerSecond = clientMessagesPerSecond,
    topicPrefix = topicPrefix,
    publishingClientPrefix = pubClientPrefix,
    subscribingClientPrefix = subClientPrefix,
    randomizeClientPrefix = randomizeClientPrefix,
    publishQos = qos,
    messageMinSize = messageMinSize,
    messageMaxSize = messageMaxSize)

  private val mqttToKafkaGen = for {
    rampUpSeconds <- Gen.chooseNum(0, 1000)
    actionsDuringRampUp <- Gen.oneOf(false, true)
    durationSeconds <- Gen.chooseNum(0, 10000)
    topicsNumber <- Gen.chooseNum(0, 1000000)
    topicsGroupsNumber <- Gen.chooseNum(0, 100000)
    publishingClientsNumber <- Gen.chooseNum(0, 1000000)
    clientMessagesPerSecond <- Gen.chooseNum(0.0, 100.0)
    topicPrefix <- Gen.asciiPrintableStr
    clientPrefix <- Gen.asciiPrintableStr
    randomizeClientPrefix <- Gen.oneOf(false, true)
    qos <- Gen.chooseNum(0, 2)
    messageMinSize <- Gen.chooseNum(0, 10000)
    messageMaxSize <- Gen.chooseNum(0, 10000)
    kafkaGroupId <- Gen.alphaNumStr
    kafkaTopicsList <- Gen.listOf(Gen.alphaNumStr)
  } yield ScenarioConfig.MqttToKafka(
    rampUpSeconds = rampUpSeconds,
    actionsDuringRampUp = actionsDuringRampUp,
    durationSeconds = durationSeconds,
    topicsPerNode = topicsNumber,
    topicGroupsPerNode = topicsGroupsNumber,
    publishingClientsPerNode = publishingClientsNumber,
    clientMessagesPerSecond = clientMessagesPerSecond,
    topicPrefix = topicPrefix,
    clientPrefix = clientPrefix,
    randomizeClientPrefix = randomizeClientPrefix,
    qos = qos,
    messageMinSize = messageMinSize,
    messageMaxSize = messageMaxSize,
    kafkaGroupId = kafkaGroupId,
    kafkaTopics = kafkaTopicsList.mkString(",") )


  private val scenarioConfigGen: Gen[ScenarioConfig] = Gen.oneOf(mqttPublishOnlyGen, mqttToMqttGen, mqttToKafkaGen)

  "ScenarioConfig" should "be serializable" in {
    forAll((scenarioConfigGen, "ScenarioConfig")) { scenarioConfig =>
      val serialized = scenarioConfig.toJson
      val deserialized = serialized.fromJson[ScenarioConfig]
      deserialized.right.value shouldBe scenarioConfig
    }
  }

  it should "parse MqttPublishOnly" in {
    val config = MqttTestSuiteConfig.load()
    val scenarioConfig = ScenarioConfig.load(config.copy(scenarioConfigPath = Option("src/test/resource/sampleScenarioConfig/mqttPublishOnly.conf")))
    scenarioConfig.scenarioName should be ("mqttPublishOnly")
    scenarioConfig shouldBe a [ScenarioConfig.MqttPublishOnly]

    val c = scenarioConfig.asInstanceOf[ScenarioConfig.MqttPublishOnly]
    c.topicsPerNode shouldBe 25
  }

  it should "parse MqttToKafka" in {
    val config = MqttTestSuiteConfig.load()
    val scenarioConfig = ScenarioConfig.load(config.copy(scenarioConfigPath = Option("src/test/resource/sampleScenarioConfig/mqttToKafka.conf")))
    scenarioConfig.scenarioName should be ("mqttToKafka")
    scenarioConfig shouldBe a [ScenarioConfig.MqttToKafka]

    val c = scenarioConfig.asInstanceOf[ScenarioConfig.MqttToKafka]
    c.durationSeconds shouldBe 32

    c.connectionMonkey.intermittentClientsPercentage shouldBe 5
    c.connectionMonkey.averageClientUptimeSeconds shouldBe 200
    c.connectionMonkey.averageClientDowntimeSeconds shouldBe 15
    c.connectionMonkey.timeSdSeconds shouldBe 5
  }

  it should "parse KafkaToMqtt" in {
    val config = MqttTestSuiteConfig.load()
    val scenarioConfig = ScenarioConfig.load(config.copy(scenarioConfigPath = Option("src/test/resource/sampleScenarioConfig/kafkaToMqtt.conf")))
    scenarioConfig.scenarioName should be ("kafkaToMqtt")
    scenarioConfig shouldBe a [ScenarioConfig.KafkaToMqtt]

    val c = scenarioConfig.asInstanceOf[ScenarioConfig.KafkaToMqtt]
    c.durationSeconds shouldBe 33
  }

  it should "parse MqttToMqtt" in {
    val config = MqttTestSuiteConfig.load()
    val scenarioConfig = ScenarioConfig.load(config.copy(scenarioConfigPath = Option("src/test/resource/sampleScenarioConfig/mqttToMqtt.conf")))
    scenarioConfig.scenarioName should be ("mqttToMqtt")
    scenarioConfig shouldBe a [ScenarioConfig.MqttToMqtt]

    val c = scenarioConfig.asInstanceOf[ScenarioConfig.MqttToMqtt]
    c.durationSeconds shouldBe 34
  }
}
