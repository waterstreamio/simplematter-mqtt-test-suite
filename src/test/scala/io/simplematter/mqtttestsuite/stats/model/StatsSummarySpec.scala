package io.simplematter.mqtttestsuite.stats.model

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should

class StatsSummarySpec extends AnyFlatSpec with should.Matchers {

  "StatsSummary" should "build the report" in {
    val statsSummary = StatsSummary(mqttConnectAttempts = 100 ,
      mqttConnectFailures = 1,
      mqttConnectSuccess = 99,
      mqttConnectClose = 1,
      mqttConnectionsActive = 99,
      mqttConnectionsExpected = 100 ,
      mqttSubscribePatternsSent = 90 ,
      mqttSubscribePatternsSuccess = 90,
      mqttSubscribePatternsError = 0,
      sent =  StatsSummary.SentMessages(attempts = 1000,
        success = 1000 ,
        failure = 0,
        publishRetransmitAttempts = 0,
        pubrelRetransmitAttempts = 0,
        firstTimestamp = 10000L,
        lastTimestamp = 20000L),
      received = StatsSummary.ReceivedMessages(expectedCount = 1000,
        count = 1000,
        firstTimestamp = 10000L,
        lastTimestamp = 20000L,
        trackingSuccess = 1000,
        trackingMissing = 0 ,
        trackingUnexpected = 0,
        trackingError = 0),
      scenario = StatsSummary.Scenario.empty,
      latencyAvg = 200L,
      latencyMax = 1000L ,
      latencyP50 = 50L,
      latencyP75 = 75L ,
      latencyP95 = 100L ,
      latencyP99 = 200L)

    val reportOutput = statsSummary.buildOutput(now = 30000L, label = Some("test"), mqttBrokerConfig = None, scenarioConfig = None)

    reportOutput.title should startWith("TEST STATISTICS")

    reportOutput.items.size should be >= 10

    println("report output: " + reportOutput)
  }

}
