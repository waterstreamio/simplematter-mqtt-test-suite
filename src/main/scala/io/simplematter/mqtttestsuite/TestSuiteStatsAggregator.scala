package io.simplematter.mqtttestsuite

import io.simplematter.mqtttestsuite.TestSuiteRunner.Environment
import io.simplematter.mqtttestsuite.config.MqttTestSuiteConfig
import io.simplematter.mqtttestsuite.hazelcast.HazelcastUtil
import io.simplematter.mqtttestsuite.stats.{StatsAggregator, StatsProvider, StatsReporter}
import org.slf4j.LoggerFactory
import zio.{ExitCode, Scope, URIO, ZEnvironment, ZIO, ZIOAppArgs}

/**
 * Aggregares stat
 */
object TestSuiteStatsAggregator extends zio.ZIOAppDefault {
  private val log = LoggerFactory.getLogger(TestSuiteStatsAggregator.getClass)

  lazy val config = MqttTestSuiteConfig.load()

    def run: ZIO[Environment with ZIOAppArgs with Scope, Any, Any] = {
      val body = (for {
        statsProvider <- ZIO.service[StatsProvider]
        statsReporter = StatsReporter(config.stats, statsProvider)
        _ = log.info("Starting stats reporter")
        _ <- statsReporter.run()
      } yield ())
      body.provideSome[zio.Clock](HazelcastUtil.hazelcastInstanceLayer(config.hazelcast), StatsAggregator.layer)
        .provideEnvironment(zio.DefaultServices.live)
  }

}
