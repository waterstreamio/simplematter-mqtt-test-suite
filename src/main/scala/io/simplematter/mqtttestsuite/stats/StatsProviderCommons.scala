package io.simplematter.mqtttestsuite.stats

import com.hazelcast.core.HazelcastInstance
import io.simplematter.mqtttestsuite.stats.model.IssuesReport
import io.simplematter.mqtttestsuite.model.NodeId

import scala.jdk.CollectionConverters.*

trait StatsProviderCommons(hazelcastInstance: HazelcastInstance) extends StatsProvider {

//  override def getIssuesReport(): IssuesReport = {
//    val messagesToDeliverSet = messagesToDeliverMap.keySet().asScala
//    val unexpectedMessagesDeliveredSet = unexpectedMessagesDeliveredMap.keySet().asScala
//    IssuesReport(
//      messagesNotDelivered = messagesToDeliverSet.toSet -- unexpectedMessagesDeliveredSet.toSet,
//      unexpectedMessagedDelivered = unexpectedMessagesDeliveredSet.toSet -- messagesToDeliverSet.toSet
//    )
//  }

  protected val issuesReportMapName = "mqtt_issues_reports"
  protected lazy val issuesReportMap = hazelcastInstance.getMap[NodeId, IssuesReport](issuesReportMapName)

//  private lazy val messagesToDeliverMap = hazelcastInstance.getMap[String, Long | Null](StatsStorage.messagesToDeliverMapName)
//  private lazy val unexpectedMessagesDeliveredMap = hazelcastInstance.getMap[String, Long | Null](StatsStorage.unexpectedMessagedDeliveredMapName)
}
