package io.simplematter.mqtttestsuite

import io.simplematter.mqtttestsuite.config.MqttTestSuiteConfig
import io.simplematter.mqtttestsuite.hazelcast.HazelcastUtil
import io.simplematter.mqtttestsuite.stats.{StatsAggregator, StatsProvider, StatsReporter}
import org.slf4j.LoggerFactory
import zio.{URIO, ZEnv, ZIO}
import zio.ExitCode

/**
 * Aggregares stat
 */
object TestSuiteStatsAggregator extends zio.App {
  private val log = LoggerFactory.getLogger(TestSuiteStatsAggregator.getClass)

  lazy val config = MqttTestSuiteConfig.load()

  def run(args: List[String]): URIO[ZEnv, ExitCode] = {
    (for {
      statsProvider <- ZIO.service[StatsProvider]
      statsReporter = StatsReporter(config.stats, statsProvider)
      _ = log.info("Starting stats reporter")
      _ <- statsReporter.run()
    } yield ()).provideSomeLayer[ZEnv](HazelcastUtil.hazelcastInstanceLayer(config.hazelcast) >>> StatsAggregator.layer).exitCode
  }

}
