package io.simplematter.mqtttestsuite.kafka

import com.hazelcast.core.HazelcastInstance
import io.simplematter.mqtttestsuite.config.KafkaConfig
import zio.Has
import org.apache.kafka.common.TopicPartition as CommonTopicPartition
import org.slf4j.LoggerFactory
import zio.{Has, RIO, Task, ZIO}
import zio.blocking.Blocking

import java.util.concurrent.ConcurrentHashMap

class KafkaOffsetsCache(kafkaConfig: KafkaConfig, name: String) {

  private val offsetMap = ConcurrentHashMap[CommonTopicPartition, Long | Null]()

  /**
   * Retrieves the latest offsets for specified partitions unless offsets are found in the cache.
   * If offsets found in the cache - returns from cache.
   * If retrieved offset isn't yet available in the cache, saves it in the cache for subsequent requests.
   * If someonw has written the offset into the cache in the meantime - doesn't store it in the cache.
   *
   * @param topicsPartitions
   * @return
   */
  def getOffsets(topicsPartitions: Set[CommonTopicPartition]): RIO[Blocking, Map[CommonTopicPartition, Long]] = {
    for {
      offsetsOpt: Iterable[(CommonTopicPartition, Option[Long])] <- RIO { topicsPartitions.map { tp =>
        tp.hashCode() /* initialize hashCode to ensure correct serialization */
        tp -> (offsetMap.get(tp) match {
          case l: Long => Option(l)
          case null => None
        })
      }}
      knownOffsets: Map[CommonTopicPartition, Long] = offsetsOpt.flatMap((k, v) => v.map( k -> _)).toMap
      unknownOffsetPartitions = offsetsOpt.filter((k, v) => v.isEmpty).map(_._1)
      _ = log.debug("Cache {} offsets for partitions {} from cache: {}. Of them known: {}, unknown: {}", name, topicsPartitions, offsetsOpt, knownOffsets, unknownOffsetPartitions)
      retrievedLatestOffsets <- KafkaUtils.getLatestOffsets(kafkaConfig, unknownOffsetPartitions)
      _ = log.debug("Retrieved offsets on behalf of cache {}: {}", name, retrievedLatestOffsets)
      _ = retrievedLatestOffsets.foreach { (partition, offset) =>
        val resultOffset = offsetMap.putIfAbsent(partition, offset)
        if(resultOffset == offset) {
          log.debug("Cache {} partition {} offset set to {}", name, partition, offset)
        } else {
          log.info("Cache {} partition {} offset NOT updated to {} - it already has value {}", name, partition, offset, resultOffset)
        }
      }
    } yield knownOffsets ++ retrievedLatestOffsets
  }

  private val log = LoggerFactory.getLogger(classOf[KafkaOffsetsCache])

}
