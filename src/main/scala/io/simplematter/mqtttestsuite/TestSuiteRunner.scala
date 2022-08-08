package io.simplematter.mqtttestsuite

import com.hazelcast.core.HazelcastInstance
import io.simplematter.mqtttestsuite.config.{MqttTestSuiteConfig, ScenarioConfig}
import io.simplematter.mqtttestsuite.hazelcast.HazelcastUtil
import io.simplematter.mqtttestsuite.mqtt.MqttClient
import io.simplematter.mqtttestsuite.scenario.{MqttTestScenario, ScenarioEnv}
import io.simplematter.mqtttestsuite.stats.{FlightRecorder, StatsProvider, StatsReporter, StatsStorage}
import org.slf4j.LoggerFactory
import zio.{ExitCode, Fiber, RIO, Runtime, URIO, URLayer, ZIO, ZLayer}
import zio.Clock

import scala.concurrent.Await
import scala.concurrent.duration.*
import zio.Clock
import zio.Console
import zio.*
import zio.Duration

object TestSuiteRunner extends ZIOAppDefault {
  private val log = LoggerFactory.getLogger(TestSuiteRunner.getClass)

  private def testSuite(): RIO[Clock with Console, Unit] = {
    val config = MqttTestSuiteConfig.load()
    val scenarioConfig = ScenarioConfig.load(config)

    (for {
      _ <- HazelcastUtil.waitForMinSize(Option(config.expectedHazelcastNodesNumber), config.hazelcast.minNodesMaxWaitSeconds)
      runnerNodeIndex <- HazelcastUtil.getNodeIndex(config.expectedRunnerNodesCount, config.nodeIdNonEmpty, config.hazelcast.minNodesMaxWaitSeconds)
      scn = MqttTestScenario.create(config, scenarioConfig, runnerNodeIndex)
      _ = log.info("Starting scenario {} with node index {}", scn.name, runnerNodeIndex)
      statsReporter <- ZIO.service[StatsReporter]
      stF <- statsReporter.run().ignoreLogged.fork
      _ <- scn.start().onError(cause =>
        //fail
        for {
          flightRecorder <- FlightRecorder.live
          _ <- flightRecorder.scenarioFail()
          _ = log.error(s"Scenario ${scn.name} failed", cause.squash)
        } yield ()
      ).flatMap { postScenario =>
        //success
        for {
          _ <- ZIO.attempt { log.info(s"Scenario ${scn.name} complete, waiting ${config.completionTimeout} for the remaining messages") }
          _ <- StatsStorage.waitCompletion(config.completionTimeout)
          _ <- postScenario.interrupt
        } yield ()
      }
      _ <- stF.interrupt
      testStopTimestamp <- Clock.instant
      _ <- StatsStorage.finalizeStats()
      _ = statsReporter.printStats(testStopTimestamp.toEpochMilli(), Option(s"Final - ${testStopTimestamp}"))
      _ = MqttClient.shutdown()
    } yield ())
      .provideSomeLayer[Clock with Console](testSuiteLayer(config, scenarioConfig))
  }

  private def testSuiteLayer(config: MqttTestSuiteConfig, scenarioConfig: ScenarioConfig): ZLayer[Clock, Throwable, FlightRecorder with StatsStorage with StatsReporter with HazelcastInstance] = {
    val hz = HazelcastUtil.hazelcastInstanceLayer(config.hazelcast)

    val statsStorage = (hz ++ ZLayer.service[Clock]) >>>
        StatsStorage.layer(config.nodeIdNonEmpty, config.stats, config.stats.statsUploadInterval, config.mqtt, scenarioConfig).passthrough

    val statsReporter = ZLayer.fromZIO {
      for {
        statsStorage <- ZIO.service[StatsStorage]
      } yield StatsReporter(config.stats, statsStorage)
    }

    (statsStorage >+> statsReporter)
  }

  def run: ZIO[Environment with ZIOAppArgs with Scope, Any, Any] = testSuite().provideEnvironment(DefaultServices.live)

}
