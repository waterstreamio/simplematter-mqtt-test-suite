package io.simplematter.mqtttestsuite.model

import io.simplematter.mqtttestsuite.model.GroupedTopics.Slice

import scala.util.Random
import io.simplematter.mqtttestsuite.util.pickCircular


class GroupedTopics(val topicsByGroupId: Map[Int, Seq[MqttTopicName]], topicPrefix: String) {
  val topics: IndexedSeq[MqttTopicName] = topicsByGroupId.values.flatten.toIndexedSeq

  private def groupPattern(groupId: Int) = topicPrefix + "/" + groupId + "/#"

  /**
   * Slice groups and topics with wrap-around - i.e. when topics or groups reach the end, they start from the beginning again
   *
   * @param groupsSliceSize
   * @param topicsSliceSize
   * @param index index of the slice, 0-based
   * @return
   */
  def circularSlice(groupsSliceSize: Int, topicsSliceSize: Int, index: Int): GroupedTopics.Slice = {
    val pickedGroupedTopics = pickCircular(topicsByGroupId, groupsSliceSize, index)
    val assignedTopics = pickCircular(topics, topicsSliceSize, index)
    Slice(pickedGroupedTopics.map { (g, _) => groupPattern(g) },
      assignedTopics,
      pickedGroupedTopics.flatMap(_._2)
    )
  }

  def randomTopic(): MqttTopicName =
    topics(Random.nextInt(topics.size))
}

object GroupedTopics {
  /**
   *
   * @param groupPatterns MQTT subscription patterns for the topics assigned
   * @param assignedTopics MQTT topics directly assigned
   * @param topicsForGroups MQTT topics that match the `groupPatterns`
   */
  case class Slice(groupPatterns: Seq[String],
                   assignedTopics: Seq[MqttTopicName],
                   topicsForGroups: Seq[MqttTopicName]) {
    def patterns = groupPatterns ++ assignedTopics.map(_.value)
  }

  opaque type GroupId = Int
  object GroupId {
    def apply(id: Int): GroupId = id

    extension (group: GroupId) {
      //TBD: should we have more high-level methods ?
      def value: Int = group
    }
  }
}
