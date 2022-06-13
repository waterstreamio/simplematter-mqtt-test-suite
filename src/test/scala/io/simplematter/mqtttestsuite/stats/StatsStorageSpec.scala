package io.simplematter.mqtttestsuite.stats

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import org.scalacheck.Gen
import zio.json.*
import org.scalatest.EitherValues
import io.simplematter.mqtttestsuite.scenario.{MqttPublishOnlyScenario, MqttToKafkaScenario, MqttToMqttScenario, ScenarioState}
import io.simplematter.mqtttestsuite.model.NodeId
import io.simplematter.mqtttestsuite.stats.model.StatsSummary

class StatsStorageSpec extends AnyFlatSpec
  with should.Matchers
  with ScalaCheckPropertyChecks
  with TableDrivenPropertyChecks
  with EitherValues {

  val statsScenarioGen = for {
    scenarioName <- Gen.oneOf(Seq(MqttToKafkaScenario.name, MqttToMqttScenario.name, MqttPublishOnlyScenario.name))
    scenarioState <- Gen.oneOf(ScenarioState.values)
    rampUpStartTimestamp <- Gen.chooseNum(0L, 1000000000L)
    rampUpDurationSeconds <- Gen.chooseNum(0, 1000)
    startTimestamp <- Gen.chooseNum(0L, 1000000000L)
    stopTimestamp <- Gen.chooseNum(startTimestamp, 1100000000L)
    durationSeconds <- Gen.chooseNum(0, 10000)
  } yield StatsSummary.Scenario(scenarioName,
    scenarioState,
    1,
    rampUpStartTimestamp,
    rampUpDurationSeconds,
    startTimestamp,
    stopTimestamp,
    durationSeconds)

  val statsSentMessagesGen = for {
    attempts <- Gen.chooseNum(0, 100000)
    success <- Gen.chooseNum(0, 100000)
    failure <- Gen.chooseNum(0, 100000)
    pubRetransmitAttempts <- Gen.chooseNum(0, 10000)
    pubrelRetransmitAttempts <- Gen.chooseNum(0, 10000)
    firstTimestamp <- Gen.chooseNum(0L, 1000000000L)
    lastTimestamp <- Gen.chooseNum(firstTimestamp, 1000000000L)
  } yield StatsSummary.SentMessages(attempts, success, failure, pubRetransmitAttempts, pubrelRetransmitAttempts, firstTimestamp, lastTimestamp)

  val statsReceivedMessagesGen = for {
    expectedCount <- Gen.chooseNum(0, 100000)
    count <- Gen.chooseNum(0, 100000)
    firstTimestamp <- Gen.chooseNum(0L, 1000000000L)
    lastTimestamp <- Gen.chooseNum(firstTimestamp, 1000000000L)
    trackingSuccess <- Gen.chooseNum(0, count)
    trackingMissing <- Gen.chooseNum(0, count)
    trackingUnexpected <- Gen.chooseNum(0, count)
    trackingError <- Gen.chooseNum(0, count)
  } yield StatsSummary.ReceivedMessages(expectedCount, count, firstTimestamp, lastTimestamp,
    trackingSuccess, trackingMissing, trackingUnexpected, trackingError)

  val statsSnapshotGen = for {
    nodeId <- Gen.asciiPrintableStr
    snapshotId <- Gen.chooseNum(1L, 1000000L)
    mqttConnectAttempts <- Gen.chooseNum(0, 1000)
    mqttConnectFailures <- Gen.chooseNum(0, 1000)
    mqttConnectSuccess <- Gen.chooseNum(0, 1000)
    mqttConnectClose <- Gen.chooseNum(0, 1000)
    mqttConnectionsActive <- Gen.chooseNum(0, 1000)
    mqttConnectionsExpected <- Gen.chooseNum(0, 1000)
    mqttSubscribePatternsSent <- Gen.chooseNum(0, 1000)
    mqttSubscribePatternsSuccess <- Gen.chooseNum(0, 1000)
    mqttSubscribePatternsError <- Gen.chooseNum(0, 1000)
    sent <- statsSentMessagesGen
    received <- statsReceivedMessagesGen
    scenario <- statsScenarioGen
    latencySum <- Gen.chooseNum[Long](0L, 1000000L)
    latencyMax <- Gen.chooseNum[Long](0L, 1000000L)
    quantileBuckets <- Gen.listOf(Gen.chooseNum(0, 100000))
  } yield StatsStorage.Snapshot(
    nodeId = NodeId(nodeId),
    snapshotId = snapshotId,
    mqttConnectAttempts = mqttConnectAttempts,
    mqttConnectFailures = mqttConnectFailures,
    mqttConnectSuccess = mqttConnectSuccess,
    mqttConnectClose = mqttConnectClose,
    mqttConnectionsActive = mqttConnectionsActive,
    mqttConnectionsExpected = mqttConnectionsExpected,
    mqttSubscribePatternsSent = mqttSubscribePatternsSent,
    mqttSubscribePatternsSuccess = mqttSubscribePatternsSuccess,
    mqttSubscribePatternsError = mqttSubscribePatternsError,
    sent = sent,
    received = received,
    scenario = scenario,
    latencySum = latencySum,
    latencyMax = latencyMax,
    quantileBuckets = quantileBuckets
  )

  "Snapshot" should "serialize and deserialize JSON" in {
    forAll((statsSnapshotGen, "stats snapshot")) { statsSnapshot =>
      val serialized =  statsSnapshot.toJson
      val deserialized = serialized.fromJson[StatsStorage.Snapshot]
      deserialized.right.value shouldBe statsSnapshot
    }
  }
}
