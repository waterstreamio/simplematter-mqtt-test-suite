package io.simplematter.mqtttestsuite.config

import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.*
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import zio.json.*
import org.scalatest.EitherValues

class MqttTestSuiteConfigSpec extends AnyFlatSpec
  with should.Matchers
  with ScalaCheckPropertyChecks
  with EitherValues {

  private val mqttBrokerConfigGen = for {
    serverHost <- Gen.alphaNumStr
    serverPort <- Gen.chooseNum(1, 1000)
    connectionTimeoutSeconds <- Gen.chooseNum(0, 1000)
    subscribeTimeoutSeconds <- Gen.chooseNum(0, 1000)
    keepAliveSeconds <- Gen.chooseNum(0, 1000)
    autoKeepAlive <- Gen.oneOf(false, true)
    maxRetransmissionAttempts <- Gen.chooseNum(0, 10)
    statusCheckIntervalSeconds <- Gen.chooseNum(0, 100)
  } yield MqttBrokerConfig(server = s"$serverHost:$serverPort",
    connectionTimeoutSeconds = connectionTimeoutSeconds,
    subscribeTimeoutSeconds = subscribeTimeoutSeconds,
    keepAliveSeconds = keepAliveSeconds,
    autoKeepAlive = autoKeepAlive,
    maxRetransmissionAttempts = maxRetransmissionAttempts,
    statusCheckIntervalSeconds = statusCheckIntervalSeconds)


  "MqttTestSuiteConfig" should "be parsed" in {
    val config = MqttTestSuiteConfig.load()
    config.expectedRunnerNodesCount shouldBe 1
    config.expectedAggregatorNodesCount shouldBe 0
    config.stats.statsPortIntOption shouldBe None
  }

  "MqttBrokerConfig" should "be serializable" in {
    forAll((mqttBrokerConfigGen, "MqttBrokerConfig")) { mqttBrokerConfig =>
      val serialized = mqttBrokerConfig.toJson
      val deserialized = serialized.fromJson[MqttBrokerConfig]
      deserialized.right.value shouldBe mqttBrokerConfig
    }
  }


}
