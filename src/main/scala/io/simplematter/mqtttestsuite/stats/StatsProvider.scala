package io.simplematter.mqtttestsuite.stats

import io.simplematter.mqtttestsuite.config.MqttBrokerConfig
import io.simplematter.mqtttestsuite.config.ScenarioConfig
import io.simplematter.mqtttestsuite.stats.model.{IssuesReport, StatsSummary}

trait StatsProvider {
  def getStats(): StatsSummary

  def getIssuesReport(): IssuesReport

  def getMqttBrokerConfig(): Option[MqttBrokerConfig]

  def getScenarioConfig(): Option[ScenarioConfig]
}
