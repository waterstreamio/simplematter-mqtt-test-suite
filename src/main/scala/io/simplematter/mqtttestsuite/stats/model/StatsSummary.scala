package io.simplematter.mqtttestsuite.stats.model

import io.simplematter.mqtttestsuite.config.{MqttBrokerConfig, ScenarioConfig}
import io.simplematter.mqtttestsuite.scenario.ScenarioState
import io.simplematter.mqtttestsuite.stats.presentation.{PlainText, ReportOutputModel}
import io.simplematter.mqtttestsuite.util.minNonZero
import zio.json.*

import java.text.DecimalFormat
import java.time.Instant



case class StatsSummary(mqttConnectAttempts: Int,
                        mqttConnectFailures: Int,
                        mqttConnectSuccess: Int,
                        mqttConnectClose: Int,
                        mqttConnectionsActive: Int,
                        mqttConnectionsExpected: Int,
                        mqttSubscribePatternsSent: Int,
                        mqttSubscribePatternsSuccess: Int,
                        mqttSubscribePatternsError: Int,
                        sent: StatsSummary.SentMessages,
                        received: StatsSummary.ReceivedMessages,
                        scenario: StatsSummary.Scenario,
                        latencyAvg: Long,
                        latencyMax: Long,
                        latencyP50: Long,
                        latencyP75: Long,
                        latencyP95: Long,
                        latencyP99: Long) {
  import StatsSummary.{fmt2, fmt3}

  def buildOutput(now: Long,
                  label: Option[String] = None,
                  mqttBrokerConfig: Option[MqttBrokerConfig] = None,
                  scenarioConfig: Option[ScenarioConfig] = None): ReportOutputModel = {
    val headContent = "TEST STATISTICS" + label.fold("")(" (" + _ + ")")

    val msgTransferTimespanSeconds = ((if(received.lastTimestamp == 0) 0 else received.lastTimestamp) - sent.firstTimestamp)/1000
    val avgThroughput = if(msgTransferTimespanSeconds == 0) 0 else received.count.toDouble / msgTransferTimespanSeconds
    val receiveSuccessPercent = if(received.expectedCount == 0) 100.0 else (received.count.toDouble / received.expectedCount)*100
    val fanOutFactor = if(sent.success == 0) 0 else received.expectedCount.toDouble / sent.success

    val startTimestampTxt = Instant.ofEpochMilli(scenario.rampUpStartTimestamp).toString()
    val scenarioStopTimestampTxt = Instant.ofEpochMilli(scenario.scenarioStopTimestamp).toString()
    val rampUpRemainingTxt = scenario.rampUpRemainigTimeSeconds(now) match {
      case 0 => ""
      case t => s" RampUp remain: ${t} s, "
    }
    val remainingTimeSeconds = scenario.remainigTimeSeconds(now)

    val mqttBrokerConfigTxt = mqttBrokerConfig.fold("")(cfg => "|MQTT cfg: " + cfg.toJson + "\n|")
    val scenarioConfigTxt = scenarioConfig.fold("")(cfg => "|Scenario cfg: " + cfg.toJson + "\n")

    val avgSendingTime = if(sent.success == 0) 0 else sent.sendingDuration / sent.success
    ReportOutputModel(title = headContent,
      items = Seq(
        PlainText(s"Scenario ${scenario.nodes}*${scenario.scenarioName} ${scenario.scenarioState}. Start: ${startTimestampTxt}, stop: ${scenarioStopTimestampTxt}, ${rampUpRemainingTxt}ETA: ${remainingTimeSeconds} s"),
        PlainText(s"Conclusion: ${conclusion(scenarioConfig)}"),
        PlainText(s"MQTT active connections: ${mqttConnectionsActive}, expected: ${mqttConnectionsExpected}, attempts: ${mqttConnectAttempts}, failures: ${mqttConnectFailures}, success: ${mqttConnectSuccess}, closed: ${mqttConnectClose}"),
        PlainText(s"MQTT subscribe patterns sent: ${mqttSubscribePatternsSent}, success: ${mqttSubscribePatternsSuccess}, error: ${mqttSubscribePatternsError}"),
        PlainText(s"Sent successfully: ${sent.success}, failures: ${sent.failure}, total attempts: ${sent.attempts}. Retransmit publish: ${sent.publishRetransmitAttempts}, pubrel: ${sent.pubrelRetransmitAttempts}"),
        PlainText(s"Received: ${received.count} out of ${received.expectedCount}, success rate: ${fmt2(receiveSuccessPercent)} %, fan-out: ${fmt2(fanOutFactor)}"),
        PlainText(s"Tracking: success ${received.trackingSuccess}, missing ${received.trackingMissing}, unexpected ${received.trackingUnexpected}, error ${received.trackingError}"),
        PlainText(s"Transmission duration: $msgTransferTimespanSeconds s, avg sending time: ${avgSendingTime}"),
        PlainText(s"Throughput avg: ${fmt2(avgThroughput)} msg/sec"),
        PlainText(s"Latency avg: ${latencyAvg}, max: ${latencyMax}, P50/75/95/99: ${latencyP50}/${latencyP75}/${latencyP95}/${latencyP99}"),
      ) ++
        mqttBrokerConfig.map(cfg => PlainText("MQTT cfg: " + cfg.toJson)).toSeq ++
        scenarioConfig.map(cfg => PlainText("Scenario cfg: " + cfg.toJson)).toSeq
    )
  }

  def conclusion(scenarioConfig: Option[ScenarioConfig]): StatsSummary.Conclusion = {
    if (scenario.scenarioState == ScenarioState.Fail) {
      StatsSummary.Conclusion.TechicalError
    } else if (scenario.scenarioState == ScenarioState.Done) {
      var errors = List[String]()
      var warnings = List[String]()

      def addError(msg: String): Unit = { errors = msg +: errors }
      def addWarn(msg: String): Unit = { warnings = msg +: warnings }

      val guaranteesNoDup = scenarioConfig.fold(true)(_.guaranteesNoDuplicates)
      val guaranteesNoLost = scenarioConfig.fold(true)(_.guaranteesNoLost)

      if(received.count < received.expectedCount && guaranteesNoLost) addError("Not all messages received")
      if(received.count < received.expectedCount && !guaranteesNoLost) addWarn("Not all messages received")
      if(received.trackingMissing > 0 && guaranteesNoLost) addError("Tracking has missing messages")
      if(received.trackingMissing > 0 && !guaranteesNoLost) addWarn("Tracking has missing messages")
      if(sent.success < sent.attempts) addError("Not all messages successfully sent")

      if(received.count > received.expectedCount && guaranteesNoDup) addError("Received more than expected")
      if(received.count > received.expectedCount && !guaranteesNoDup) addWarn("Received more than expected")
      if(received.trackingUnexpected > 0 && guaranteesNoDup) addError("Tracking has unexpected messages")
      if(received.trackingUnexpected > 0 && !guaranteesNoDup) addWarn("Tracking has unexpected messages")

      if( mqttConnectSuccess < mqttConnectionsExpected ) addError("Not all expected clients connected")
      if( mqttConnectSuccess < mqttConnectAttempts ) addWarn("Not all connection attempts were successful")

      if(errors.nonEmpty)
        StatsSummary.Conclusion.Fail(errors.reverse.mkString(", "))
      else if(warnings.nonEmpty)
        StatsSummary.Conclusion.Warn(warnings.reverse.mkString(", "))
      else
        StatsSummary.Conclusion.Success
    } else {
      StatsSummary.Conclusion.InProgress
    }
  }

}

object StatsSummary {

  case class SentMessages(attempts: Int,
                          success: Int,
                          failure: Int,
                          publishRetransmitAttempts: Int,
                          pubrelRetransmitAttempts: Int,
                          firstTimestamp: Long,
                          lastTimestamp: Long,
                          sendingDuration: Long) {
    def agg(other: SentMessages): SentMessages = {
      if(this == SentMessages.empty)
        other
      else if(other == SentMessages.empty)
        this
      else SentMessages(attempts = attempts + other.attempts,
        success = success + other.success,
        failure = failure + other.failure,
        publishRetransmitAttempts = publishRetransmitAttempts + other.pubrelRetransmitAttempts,
        pubrelRetransmitAttempts = pubrelRetransmitAttempts + other.pubrelRetransmitAttempts,
        firstTimestamp = minNonZero(firstTimestamp, other.firstTimestamp),
        lastTimestamp = Math.max(lastTimestamp, other.lastTimestamp),
        sendingDuration = sendingDuration + other.sendingDuration
      )
    }
  }

  object SentMessages {
    val empty = SentMessages(0, 0, 0, 0, 0, 0, 0, 0)

    implicit val encoder: JsonEncoder[SentMessages] = DeriveJsonEncoder.gen[SentMessages]
    implicit val decoder: JsonDecoder[SentMessages] = DeriveJsonDecoder.gen[SentMessages]
  }

  case class ReceivedMessages(expectedCount: Int,
                              count: Int,
                              firstTimestamp: Long,
                              lastTimestamp: Long,
                              trackingSuccess: Int,
                              trackingMissing: Int,
                              trackingUnexpected: Int,
                              trackingError: Int) {
    def agg(other: ReceivedMessages): ReceivedMessages = {
      if(this == ReceivedMessages.empty)
        other
      else if(other == ReceivedMessages.empty)
        this
      else ReceivedMessages(
        expectedCount = expectedCount + other.expectedCount,
        count = count + other.count,
        firstTimestamp = minNonZero(firstTimestamp, other.firstTimestamp),
        lastTimestamp = Math.max(lastTimestamp, other.lastTimestamp),
        trackingSuccess = trackingSuccess + other.trackingSuccess,
        trackingMissing = trackingMissing + other.trackingMissing,
        trackingUnexpected = trackingUnexpected + other.trackingUnexpected,
        trackingError = trackingError + other.trackingError
      )
    }
  }

  object ReceivedMessages {
    val empty = ReceivedMessages(0, 0, 0, 0, 0, 0, 0, 0)

    implicit val encoder: JsonEncoder[ReceivedMessages] = DeriveJsonEncoder.gen[ReceivedMessages]
    implicit val decoder: JsonDecoder[ReceivedMessages] = DeriveJsonDecoder.gen[ReceivedMessages]
  }


  case class Scenario(scenarioName: String,
                      scenarioState: ScenarioState,
                      nodes: Int,
                      rampUpStartTimestamp: Long,
                      rampUpDurationSeconds: Int,
                      startTimestamp: Long,
                      scenarioStopTimestamp: Long,
                      durationSeconds: Int) {
    def agg(other: Scenario): Scenario = {
      if(this == Scenario.empty)
        other
      else if(other == Scenario.empty)
        this
      else {
        val expectedEndTimestamp = Math.max(startTimestamp + durationSeconds*1000L, other.startTimestamp + other.durationSeconds*1000L)
        val aggStartTimestamp = minNonZero(startTimestamp, other.startTimestamp)
        Scenario(
          scenarioName = if (scenarioName == other.scenarioName) scenarioName else s"${scenarioName}, ${other.scenarioName}",
          scenarioState = scenarioState.agg(other.scenarioState),
          nodes = nodes + other.nodes,
          rampUpStartTimestamp = minNonZero(rampUpStartTimestamp, other.rampUpStartTimestamp),
          rampUpDurationSeconds = Math.max(rampUpDurationSeconds, other.rampUpDurationSeconds),
          startTimestamp = aggStartTimestamp,
          scenarioStopTimestamp = Math.max(scenarioStopTimestamp, other.scenarioStopTimestamp),
          durationSeconds = ((expectedEndTimestamp - aggStartTimestamp)/1000).toInt
        )
      }
    }

    def rampUpRemainigTimeSeconds(now: Long): Int = {
      scenarioState match {
        case ScenarioState.RampUp =>
          rampUpDurationSeconds - ((now - rampUpStartTimestamp) / 1000L).toInt
        case ScenarioState.New =>
          rampUpDurationSeconds
        case _ =>
          0
      }
    }

    def remainigTimeSeconds(now: Long): Int = {
      if(scenarioState.endState)
        0
      else if(startTimestamp > 0L) {
        durationSeconds - ((now - startTimestamp) / 1000L).toInt
      } else if(rampUpStartTimestamp > 0L) {
        (durationSeconds + rampUpDurationSeconds) - ((now - rampUpStartTimestamp)/1000L).toInt
      } else {
        (durationSeconds + rampUpDurationSeconds)
      }
    }
  }

  object Scenario {
    val empty = Scenario("", ScenarioState.New, 0, 0L, 0, 0L, 0L, 0)

    implicit val encoder: JsonEncoder[Scenario] = DeriveJsonEncoder.gen[Scenario]
    implicit val decoder: JsonDecoder[Scenario] = DeriveJsonDecoder.gen[Scenario]
  }

  sealed trait Conclusion {
  }

  object Conclusion {
    case object InProgress extends Conclusion
    case object Success extends Conclusion
    case class Fail(message: String) extends Conclusion {
      override def toString: String = s"ERR: ${message}"
    }
    case class Warn(message: String) extends Conclusion {
      override def toString: String = s"WARN: ${message}"
    }
    case object TechicalError extends Conclusion
  }

//  private def fmt3(num: Double): String = String.format("%.3f", num.asInstanceOf[java.lang.Double])
  private def fmt2(num: Double): String = String.format("%.2f", num)
  private def fmt3(num: Double): String = String.format("%.3f", num)
}