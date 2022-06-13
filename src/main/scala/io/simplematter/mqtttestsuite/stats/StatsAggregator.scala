package io.simplematter.mqtttestsuite.stats

import com.hazelcast.client.HazelcastClient
import com.hazelcast.client.config.ClientConfig
import io.simplematter.mqtttestsuite.util.QuantileApprox
import io.simplematter.mqtttestsuite.config.{HazelcastConfig, MqttBrokerConfig, ScenarioConfig}
import io.simplematter.mqtttestsuite.scenario.ScenarioState
import org.slf4j.LoggerFactory
import zio.{RIO, Schedule, UIO, URIO, ZIO, clock}
import zio.clock.Clock
import zio.duration.*
import zio.Has
import zio.{TaskLayer, ULayer, URLayer, ZLayer}
import zio.json.*

import scala.jdk.CollectionConverters.*
import java.util.concurrent.{ConcurrentHashMap, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import com.hazelcast.core.Hazelcast
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.topic.Message
import com.hazelcast.topic.MessageListener
import io.simplematter.mqtttestsuite.model.NodeId
import io.simplematter.mqtttestsuite.stats.StatsStorage.{messagesToDeliverMapName, mqttBrokerConfigMapName, scenarioConfigMapName}
import io.simplematter.mqtttestsuite.stats.model.{IssuesReport, StatsSummary}

class StatsAggregator(hazelcastInstance: HazelcastInstance) extends StatsProvider with StatsProviderCommons(hazelcastInstance) {
  import StatsAggregator.log

//  private val messagesSent: AtomicInteger = AtomicInteger(0)
//  private val messagesReceived: AtomicInteger = AtomicInteger(0)
//  private val latenciesSum: AtomicLong = AtomicLong(0)
//  private val maxLatency: AtomicLong = AtomicLong(0)

//  private val sleepingInterval = 1.second


  private val nodesSnapshot = ConcurrentHashMap[NodeId, StatsStorage.Snapshot]()

  def getStats(): StatsSummary = {
    val quantileApprox = QuantileApprox(1, 10)
    val snapshotAgg = nodesSnapshot.values().asScala.foldLeft(StatsAggregator.SnapshotAgg())({(a, s) =>
      quantileApprox.aggregate(QuantileApprox.Snapshot(s.quantileBuckets))
      a.agg(s)
    })

    StatsSummary(
      mqttConnectAttempts = snapshotAgg.mqttConnectAttempts,
      mqttConnectFailures = snapshotAgg.mqttConnectFailures,
      mqttConnectSuccess = snapshotAgg.mqttConnectSuccess,
      mqttConnectClose = snapshotAgg.mqttConnectClose,
      mqttConnectionsActive = snapshotAgg.mqttConnectionsActive,
      mqttConnectionsExpected = snapshotAgg.mqttConnectionsExpected,
      mqttSubscribePatternsSent = snapshotAgg.mqttSubscribePatternsSent,
      mqttSubscribePatternsSuccess = snapshotAgg.mqttSubscribePatternsSuccess,
      mqttSubscribePatternsError = snapshotAgg.mqttSubscribePatternsError,
      sent = snapshotAgg.sent,
      received = snapshotAgg.received,
      scenario= snapshotAgg.scenario,
      latencyAvg = snapshotAgg.latencyAvg,
      latencyMax = snapshotAgg.latencyMax,
      latencyP50 = quantileApprox.quantile(0.5),
      latencyP75 = quantileApprox.quantile(0.75),
      latencyP95 = quantileApprox.quantile(0.95),
      latencyP99 = quantileApprox.quantile(0.99)
    )
  }



  override def getMqttBrokerConfig(): Option[MqttBrokerConfig] = {
    //TODO cache
    val configs = mqttBrokerConfigMap.values().asScala.flatMap(_.fromJson[MqttBrokerConfig].toOption).toSet
    if(configs.size > 1) {
      log.warn(s"More than one version of MqttBrokerConfig: ${configs}")
    }
    configs.headOption
  }

  override def getScenarioConfig(): Option[ScenarioConfig] = {
    //TODO cache
    val configs = scenarioConfigMap.values().asScala.flatMap(_.fromJson[ScenarioConfig].toOption).toSet
    if(configs.size > 1) {
      log.warn(s"More than one version of ScenarioConfig: ${configs}")
    }
    configs.headOption
  }

  override def getIssuesReport(): IssuesReport = {
    issuesReportMap.values().asScala.foldLeft(IssuesReport())((acc, item) => acc.aggregate(item))
  }


  def registerSnapshot(snapshot: StatsStorage.Snapshot): Unit = {
    nodesSnapshot.put(snapshot.nodeId, snapshot)
    statsAcknowledgeTopic.publish(StatsStorage.SnapshotAck(snapshot.nodeId, snapshot.snapshotId).toJson)
  }

  def listenForUpdates(): Unit = {
    log.debug(s"Listening for stats reporting on topic ${StatsStorage.statsReportingTopicName}")

    val l = statsReportingTopic.addMessageListener(new MessageListener[String] {
      def onMessage(msg: Message[String]): Unit = {
        msg.getMessageObject.fromJson[StatsStorage.Snapshot].fold({ decodingError =>
          log.error("Failed to decode a snapshot {}: {}", msg.getMessageObject, decodingError)
        }, { snapshot =>
          log.debug("Got stats snapshot: {}", snapshot)
          registerSnapshot(snapshot)
        })
      }
    })
  }

  private lazy val statsReportingTopic = hazelcastInstance.getTopic[String](StatsStorage.statsReportingTopicName)
  private lazy val statsAcknowledgeTopic = hazelcastInstance.getTopic[String](StatsStorage.statsAcknowledgeTopicName)
  private lazy val mqttBrokerConfigMap = hazelcastInstance.getMap[NodeId, String](StatsStorage.mqttBrokerConfigMapName)
  private lazy val scenarioConfigMap = hazelcastInstance.getMap[NodeId, String](StatsStorage.scenarioConfigMapName)
}

object StatsAggregator {
  private val log = LoggerFactory.getLogger(classOf[StatsAggregator])

  private case class SnapshotAgg(mqttConnectAttempts: Int = 0,
                                 mqttConnectFailures: Int = 0,
                                 mqttConnectSuccess: Int = 0,
                                 mqttConnectClose: Int = 0,
                                 mqttConnectionsActive: Int = 0,
                                 mqttConnectionsExpected: Int = 0,
                                 mqttSubscribePatternsSent: Int = 0,
                                 mqttSubscribePatternsSuccess: Int = 0,
                                 mqttSubscribePatternsError: Int = 0,
                                 sent: StatsSummary.SentMessages = StatsSummary.SentMessages.empty,
                                 received: StatsSummary.ReceivedMessages = StatsSummary.ReceivedMessages.empty,
                                 scenario: StatsSummary.Scenario= StatsSummary.Scenario.empty,
                                 latencySum: Long = 0,
                                 latencyMax: Long = 0) {
    def agg(snapshot: StatsStorage.Snapshot): SnapshotAgg =
      copy(
        mqttConnectAttempts = mqttConnectAttempts + snapshot.mqttConnectAttempts,
        mqttConnectFailures = mqttConnectFailures + snapshot.mqttConnectFailures,
        mqttConnectSuccess = mqttConnectSuccess + snapshot.mqttConnectSuccess,
        mqttConnectClose = mqttConnectClose + snapshot.mqttConnectClose,
        mqttConnectionsActive = mqttConnectionsActive + snapshot.mqttConnectionsActive,
        mqttConnectionsExpected = mqttConnectionsExpected + snapshot.mqttConnectionsExpected,
        mqttSubscribePatternsSent = mqttSubscribePatternsSent + snapshot.mqttSubscribePatternsSent,
        mqttSubscribePatternsSuccess = mqttSubscribePatternsSuccess + snapshot.mqttSubscribePatternsSuccess,
        mqttSubscribePatternsError = mqttSubscribePatternsError + snapshot.mqttSubscribePatternsError,
        sent = sent.agg(snapshot.sent),
        received = received.agg(snapshot.received),
        scenario = scenario.agg(snapshot.scenario),
        latencySum = latencySum + snapshot.latencySum,
        latencyMax = Math.max(latencyMax, snapshot.latencyMax)
      )

    def latencyAvg: Long = if(received.count == 0) 0L else latencySum / received.count
  }

  val layer: URLayer[Has[HazelcastInstance], Has[StatsProvider]] =
    ZLayer.requires[Has[HazelcastInstance]].map { hasHc =>
      val agg = StatsAggregator(hasHc.get[HazelcastInstance])
      agg.listenForUpdates()
      Has(agg)
    }
}