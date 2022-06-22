package io.simplematter.mqtttestsuite

import com.hazelcast.core.HazelcastInstance
import io.simplematter.mqtttestsuite.config.{MqttTestSuiteConfig, ScenarioConfig}
import io.simplematter.mqtttestsuite.hazelcast.HazelcastUtil
import io.simplematter.mqtttestsuite.mqtt.MqttClient
import io.simplematter.mqtttestsuite.scenario.{MqttTestScenario, ScenarioEnv}
import io.simplematter.mqtttestsuite.stats.{FlightRecorder, StatsProvider, StatsReporter, StatsStorage}
import org.slf4j.LoggerFactory
import zio.{ExitCode, Fiber, Has, RIO, Runtime, URIO, URLayer, ZEnv, ZIO, ZLayer, clock}

import scala.concurrent.Await
import scala.concurrent.duration.*
import zio.clock.Clock
import zio.blocking.Blocking
import zio.console.Console
import zio.*
import zio.duration.*

object TestSuiteRunner extends zio.App {
  private val log = LoggerFactory.getLogger(TestSuiteRunner.getClass)

  private def testSuite(): RIO[Clock with Blocking with Console, Unit] = {
    val config = MqttTestSuiteConfig.load()
    val scenarioConfig = ScenarioConfig.load(config)

    (for {
      _ <- HazelcastUtil.waitForMinSize(Option(config.expectedHazelcastNodesNumber), config.hazelcast.minNodesMaxWaitSeconds)
      runnerNodeIndex <- HazelcastUtil.getNodeIndex(config.expectedRunnerNodesCount, config.nodeIdNonEmpty, config.hazelcast.minNodesMaxWaitSeconds)
      scn = MqttTestScenario.create(config, scenarioConfig, runnerNodeIndex)
      _ = log.info("Starting scenario {} with node index {}", scn.name, runnerNodeIndex)
      statsReporter <- ZIO.service[StatsReporter]
      stF <- statsReporter.run().fork
//      postScenario <- scn.start()
//      _ = log.info(s"Scenario ${scn.name} complete, waiting ${config.completionTimeout} for the remaining messages")
//      _ <- StatsStorage.waitCompletion(config.completionTimeout)
//      _ <- stF.interrupt
//      _ <- postScenario.interrupt
      //TODO try again with flatMap
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
          _ <- ZIO { log.info(s"Scenario ${scn.name} complete, waiting ${config.completionTimeout} for the remaining messages") }
          _ <- StatsStorage.waitCompletion(config.completionTimeout)
          _ <- postScenario.interrupt
        } yield ()
      }
      _ <- stF.interrupt
      testStopTimestamp <- clock.instant
      _ <- StatsStorage.finalizeStats()
      _ = statsReporter.printStats(testStopTimestamp.toEpochMilli(), Option(s"Final - ${testStopTimestamp}"))
      _ = MqttClient.shutdown()
    } yield ())
      .provideSomeLayer[Clock with Blocking with Console](testSuiteLayer(config, scenarioConfig))
  }

  private def testSuiteLayer(config: MqttTestSuiteConfig, scenarioConfig: ScenarioConfig): ZLayer[Clock with Blocking, Throwable, Has[FlightRecorder] with Has[StatsStorage] with Has[StatsReporter] with Has[HazelcastInstance] ] = {
    val hz = HazelcastUtil.hazelcastInstanceLayer(config.hazelcast)

    val statsStorage = (hz ++ ZLayer.requires[Clock] ++ ZLayer.requires[Blocking]) >>>
        StatsStorage.layer(config.nodeIdNonEmpty, config.stats, config.stats.statsUploadInterval, config.mqtt, scenarioConfig).passthrough

    val statsReporter = ZLayer.fromService[StatsStorage, StatsReporter](statsStorage => StatsReporter(config.stats, statsStorage))

    (statsStorage >>> statsReporter.passthrough).passthrough
  }

  def run(args: List[String]): URIO[ZEnv, ExitCode] = testSuite().exitCode

}
