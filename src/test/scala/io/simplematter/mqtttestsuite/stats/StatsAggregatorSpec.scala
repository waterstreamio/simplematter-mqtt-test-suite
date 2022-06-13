package io.simplematter.mqtttestsuite.stats

import com.hazelcast.core.{Hazelcast, HazelcastInstance}
import io.simplematter.mqtttestsuite.config.{MqttBrokerConfig, ScenarioConfig, StatsConfig}
import org.scalacheck.Gen
import org.scalacheck.Prop.forAllNoShrink
import org.scalactic.TolerantNumerics
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should
import org.scalatest.BeforeAndAfterAll
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import zio.{Exit, Has, ZEnv, ZIO, clock}
import zio.clock.Clock
import zio.test.environment.TestClock
import zio.duration.*
import io.simplematter.mqtttestsuite.model.{MessageId, NodeId, ClientId, MqttTopicName}

import java.util.concurrent.{ConcurrentHashMap, TimeUnit}
import scala.util.Random

class StatsAggregatorSpec extends AnyFlatSpec
  with should.Matchers
  with ScalaCheckPropertyChecks
  with TableDrivenPropertyChecks
  with BeforeAndAfterAll {

  private def createHazelcastInstance(): HazelcastInstance = {
    val hzConfig = new com.hazelcast.config.Config()
    val joinCfg = hzConfig.getNetworkConfig.getJoin
    joinCfg.getAutoDetectionConfig.setEnabled(false)
    joinCfg.getMulticastConfig.setEnabled(false)
    joinCfg.getTcpIpConfig.setEnabled(false)

    val hzInst = Hazelcast.newHazelcastInstance(hzConfig)
    hzInst
  }

  private val hz1 = createHazelcastInstance()
  private val hz2 = createHazelcastInstance()

  private val sampleMqttBrokerConfig = MqttBrokerConfig("sampleServer:1883")
  private val sampleScnearioConfig = ScenarioConfig.MqttToKafka()
  private val sampleStatsConfig = StatsConfig(individualMessageTracking = false, statsPort = None)

  override def afterAll(): Unit = {
    hz1.shutdown()
    hz2.shutdown()
  }

  "QuantileApprox" should "aggregate the data from StatsStorage" in {
    val maxValue = 100000L
    //To avoid shrinking and speed up tests use the pre-generated table rather than Gen
    val client1Id = ClientId("a")
    val client2Id = ClientId("b")
    val client3Id = ClientId("c")
    val testData = Table(
      ("Sample latencies 1", "Sample latencies 2"),
      Seq.fill(10)(
        (Seq.fill(30)(Random.nextLong(maxValue)).zipWithIndex.map((l, i) => (l, MessageId(client1Id, i)))),
        (Seq.fill(15)(Random.nextLong(maxValue)).zipWithIndex.map((l, i) => (l, MessageId(client2Id, i)))),
      ): _*
    )
    val quantileLevels = Seq(0.5, 0.75, 0.9, 0.95, 0.99)
    val firstBucket = 1
    val nextBucketPercent = 10
    val expectedAccuracy = 0.05

    val testLayer = zio.test.environment.testEnvironment

    val messagesTimestamp = ConcurrentHashMap[MessageId, Long]()

    forAll(testData) { (samples1: Seq[(Long, MessageId)], samples2: Seq[(Long, MessageId)]) =>
      def recordMessages(recorder: FlightRecorder, messages: Seq[(Long, MessageId)]): Unit = {
        val testEff = for {
            _ <- TestClock.setTime((maxValue * 100).millisecond) //Give space for calculating the latency
            _ <- ZIO.collectAll(messages.map { (latency, id) =>
              for {
                newNow <- clock.currentTime(TimeUnit.MILLISECONDS)
                prevNow = messagesTimestamp.putIfAbsent(id, newNow) //To make sure same message gets sent at the same timestamp for both recorders
                agreedNow = messagesTimestamp.get(id)
                _ <- TestClock.setTime(agreedNow.millisecond)
                _ <- recorder.recordMessageSend(id, ZIO.succeed(()), MqttTopicName("fakeTopic"), None)
                _ <- TestClock.adjust(latency.millisecond)
                _ <- recorder.messageReceived(id, "fakeTopic", client3Id, agreedNow - latency, agreedNow)
              } yield()
            })
          } yield ()

          zio.Runtime.default.unsafeRunSync(testEff.provideLayer(testLayer))
      }

      val storage1 = new StatsStorage(NodeId("node1"), sampleStatsConfig, hz1, sampleMqttBrokerConfig, sampleScnearioConfig)
      val storage2 = new StatsStorage(NodeId("node2"), sampleStatsConfig, hz1, sampleMqttBrokerConfig, sampleScnearioConfig)
      val agg = new StatsAggregator(hz1)

      val storage1and2 = new StatsStorage(NodeId("node1and2"), sampleStatsConfig, hz2, sampleMqttBrokerConfig, sampleScnearioConfig)

      recordMessages(storage1, samples1)
      recordMessages(storage2, samples2)
      //Hazelcast not used for communication here, so need to register the snapshot manually
      agg.registerSnapshot(storage1.getSnapshot())
      agg.registerSnapshot(storage2.getSnapshot())

      recordMessages(storage1and2, samples1)
      recordMessages(storage1and2, samples2)

      val stats1and2 = storage1and2.getStats()
      agg.getStats() shouldBe stats1and2.copy(scenario = stats1and2.scenario.copy(nodes = 2))

    }
  }


}
