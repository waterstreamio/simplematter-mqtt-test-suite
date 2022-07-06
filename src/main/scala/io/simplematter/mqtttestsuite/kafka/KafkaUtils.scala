package io.simplematter.mqtttestsuite.kafka

import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import io.simplematter.mqtttestsuite.config.KafkaConfig
import io.simplematter.mqtttestsuite.model.{NodeId, NodeIndex}
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.slf4j.LoggerFactory
import zio.{RIO, Task, ZIO}
import zio.ZLayer
import zio.Clock
import zio.kafka.admin.AdminClient
import zio.kafka.admin.AdminClientSettings
import zio.kafka.admin.AdminClient.ListOffsetsOptions
import zio.kafka.admin.AdminClient.TopicPartition as AdminTopicPartition
import org.apache.kafka.common.TopicPartition as CommonTopicPartition

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException
import scala.jdk.CollectionConverters.*
import zio.Schedule
import zio.Duration

object KafkaUtils {
  private val log = LoggerFactory.getLogger(KafkaUtils.getClass)

  def getThisNodePartitions(nodeIndex: NodeIndex, kafkaConfig: KafkaConfig, topicNames: Seq[String], maxWaitSeconds: Int): RIO[Clock, Seq[CommonTopicPartition]] = {
    val adminSettings = AdminClientSettings(kafkaConfig.bootstrapServersSeq.toList).withProperties(kafkaConfig.adminProperties)
    ZIO.scoped {
      for {
        adminClient <- AdminClient.make(adminSettings)
        topicsDescription  <- adminClient.describeTopics(topicNames)
        _ = log.debug("getThisNodePartitions fetched description of topics {}: {}", topicNames, topicsDescription)
        allTopicsPartitions = topicsDescription.values.flatMap(topicDescription => topicDescription.partitions.map(pInfo => CommonTopicPartition(topicDescription.name, pInfo.partition))).toSeq.sortBy(_.toString)
        thisSlice = nodeIndex.pick(allTopicsPartitions)
        _ = log.info(s"This node with idx {} gets {} partitions out of {}: {}", nodeIndex, thisSlice.size, allTopicsPartitions.size, thisSlice)
      } yield thisSlice
    }
  }

  private def offsetsMapName(group: String) = s"kafka_offsets_${group}"

  private def getOffsetsMap(group: String): RIO[HazelcastInstance, IMap[CommonTopicPartition, Long | Null]] = {
    ZIO.service[HazelcastInstance].map { hz =>
      hz.getMap[CommonTopicPartition, Long | Null](offsetsMapName(group))
    }
  }

  def getLatestOffsets(kafkaConfig: KafkaConfig, topicsPartitions: Iterable[CommonTopicPartition]): Task[Map[CommonTopicPartition, Long]] = {
    if(topicsPartitions.nonEmpty) {
      val adminSettings = AdminClientSettings(kafkaConfig.bootstrapServersSeq.toList).withProperties(kafkaConfig.adminProperties)
      ZIO.scoped {
        for {
          adminClient <- AdminClient.make(adminSettings)
          adminOffsets <-
            adminClient.listOffsets(topicsPartitions.map(tp => AdminTopicPartition(tp) -> AdminClient.OffsetSpec.LatestSpec).toMap)
        } yield adminOffsets.map((tp, offsetRes) => tp.asJava -> offsetRes.offset ).toMap
      }
    } else {
      ZIO.succeed(Map())
    }
  }

  private val nodeIdSetName = "mqtt_test_node_ids"
}
