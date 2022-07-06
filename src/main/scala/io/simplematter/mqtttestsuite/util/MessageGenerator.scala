package io.simplematter.mqtttestsuite.util

import scala.util.Random
import zio.{Task, ZIO}
import io.simplematter.mqtttestsuite.model.{GroupedTopics, MessageId, MqttTopicName, NodeIndex}

object MessageGenerator {

  @deprecated("Use `thisNodeGroupedTopics`")
  def thisNodeGroupIndexes(topicGroupsPerNode: Int, nodeIndex: NodeIndex): Seq[Int] = {
    val groupsOffset = nodeIndex.thisNode*topicGroupsPerNode
    (1 to topicGroupsPerNode).map(_ + groupsOffset)
  }

//  def thisNodeGroupPatterns(topicPrefix: String, topicGroupsPerNode: Int, nodeIndex: NodeIndex): Seq[String] = {
//    thisNodeGroupIndexes(topicGroupsPerNode, nodeIndex).map { i =>
//      topicPrefix + "/" + i + "/#"
//    }
//  }


  @deprecated("Use `thisNodeGroupedTopics`")
  def thisNodeTopics(topicPrefix: String, topicGroupsPerNode: Int, topicsPerNode: Int, nodeIndex: NodeIndex): Seq[String] = {
    val groupsOffset = nodeIndex.thisNode*topicGroupsPerNode
    val topicsOffset = nodeIndex.thisNode*topicsPerNode
    (1 to topicsPerNode).map { t =>
      val g = t % topicGroupsPerNode + 1
      topicPrefix + "/" + (g + groupsOffset) + "/" + (t + topicsOffset)
    }
  }


  def thisNodeGroupedTopics(topicPrefix: String, topicGroupsPerNode: Int, topicsPerNode: Int, nodeIndex: NodeIndex): GroupedTopics = {
    val groupsOffset = nodeIndex.thisNode*topicGroupsPerNode
    val topicsOffset = nodeIndex.thisNode*topicsPerNode
    val topicsPerGroupCeil = Math.ceil(topicsPerNode.toDouble / topicGroupsPerNode).toInt
    val topicsByGroupId = (1 to topicGroupsPerNode).map { groupIndex =>
      val topicIndexes = (groupIndex - 1) * topicsPerGroupCeil until Math.min(groupIndex * topicsPerGroupCeil, topicsPerNode)
      groupIndex + groupsOffset -> topicIndexes.map {topicIndex => MqttTopicName(topicPrefix + "/" + (groupIndex + groupsOffset) + "/" + (topicIndex + topicsOffset))}.toSeq
    }.toMap
    GroupedTopics(topicsByGroupId, topicPrefix)
  }

  def randomMessageBody(minLength: Int, maxLength: Int): String = {
    if(maxLength <= 0) {
      ""
    } else {
      val b = new StringBuilder()

      val desiredLength = if (minLength >= maxLength || minLength < 0 || maxLength < 0)
        Math.max(minLength, 0)
      else
        Random.between(minLength, maxLength)

      while (b.length < desiredLength) {
        if (b.nonEmpty)
          b.append(" ")
        b.append(randomWord())
      }

      b.substring(0, Math.min(b.length(), maxLength))
    }
  }

  def packMessage(messageId: MessageId, timestamp: Long, body: String): String = {
    messageId.value + " " + timestamp + " " + body
  }

//  def generatePackedMessage(messageId: String, timestamp: Long, minLength: Int, maxLength: Int): String = {
  def generatePackedMessage(messageId: MessageId, timestamp: Long, minLength: Int, maxLength: Int): String = {
    packMessage(messageId, timestamp, randomMessageBody(minLength, maxLength))
  }

  /**
   * `Task` effect is responsible for handling the timestamp parsing errors
   *
   * @param packedMessage
   * @return
   */
  def unpackMessagePrefix(packedMessage: String): Task[(MessageId, Long)] = {
    val messageIdStr = packedMessage.takeWhile(!_.isWhitespace)
    val timestampStr = packedMessage.drop(messageIdStr.length + 1).takeWhile(!_.isWhitespace)
    for {
      messageId <- MessageId.fromString(messageIdStr)
      timestamp <- ZIO.attempt(timestampStr.toLong)
    } yield (messageId, timestamp)
  }

  private def randomWord(): String = messageParts(Random.nextInt(messageParts.size))

  private val messageParts = Vector(
    "mqtt",
    "simple",
    "complex",
    "hello",
    "how",
    "bye",
    "thanks",
    "sensor",
    "data",
    "temperature",
    "speed",
    "altitude",
    "pressure",
    "degrees",
    "initialized",
    "ok",
    "error",
    "confirm",
    "reject",
    "open",
    "close",
    "100",
    "500",
    "3.14"
  )
}
