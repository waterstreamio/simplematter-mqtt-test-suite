package io.simplematter.mqtttestsuite.util

import io.simplematter.mqtttestsuite.model.{NodeIndex, MessageId}
import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.matchers.should
import zio.Exit
import zio.ZIO

class MessageGeneratorSpec extends AnyFlatSpec
  with should.Matchers
  with ScalaCheckPropertyChecks
  with TableDrivenPropertyChecks
  {

  private val validStringLength = Gen.choose(0, 1000000)

  "MessageGenerator" should "generate messages within length bounds" in {
    forAll((validStringLength, "minLength"), (validStringLength, "maxLength")) { (minLength: Int, maxLength: Int) =>
      val message = MessageGenerator.randomMessageBody(minLength, maxLength)
      whenever(minLength < maxLength) {
        withClue("Message length should be within bounds: ") {
          message.length should be >= minLength
          message.length should be <= maxLength
        }
      }
    }
  }

  it should "parse the message prefix" in {
    val messages = Table(
      ("message", "expected success", "expected id", "expected timestamp"),
      ("1234 2345 some message", false, "1234", 2345L),
      ("a-1234 2345 some message", true, "a-1234", 2345L),
      ("mqtt-testsuite-1344-1-4 1634281741702 some message", true, "mqtt-testsuite-1344-1-4", 1634281741702L),
      ("b-1234 2345", true, "b-1234", 2345L),
      ("b-1234 some message", false, "", 0L),
      ("some-1 1234 message", true, "some-1", 1234L),
      ("a-0 0 message", true, "a-0", 0L),
      (" 0 message", false, "", 0L),
      ("- 0 message", true, "-", 0L),
      (" 1 message", false, "", 1L),
      ("- 1 message", true, "-", 1L),
      (" 1", false, "", 1L),
      ("- 1", true, "-", 1L),
      ("1234", false, "", 0L),
      (" 1234", false, "", 1234L),
      ("- 1234", true, "-", 1234L),
      (" ", false, "", 0L),
      ("", false, "", 0L),
    )

    forAll(messages) {(msg: String, expectedSuccess: Boolean, expectedIdStr: String, expectedTimestamp: Long) =>
      val res = zio.Runtime.default.unsafeRunSync(MessageGenerator.unpackMessagePrefix(msg))
      if(expectedSuccess) {
        res match {
          case Exit.Success((actualId, actualTimestamp)) =>
            val expectedId = zio.Runtime.default.unsafeRun(MessageId.fromString(expectedIdStr))
            actualId shouldBe expectedId
            actualTimestamp shouldBe expectedTimestamp
          case Exit.Failure(cause) =>
            fail(s"Should have been successful. ${cause.squash}", cause.squash)
        }
      } else {
        res match {
          case Exit.Success(_) => fail("Should have failed")
          case Exit.Failure(cause) => //OK
        }
      }
    }
  }

  it should "generate topics for the node" in {
    val topicPrefix = "foo"
    forAll(Table(
      ("topicGroupsPerNode", "topicsPerNode", "nodesCount"),
      (1, 10, 1),
      (1, 10, 3),
      (10, 100, 1),
      (10, 100, 3)
    )) {(topicGroupsPerNode: Int, topicsPerNode: Int, nodesCount: Int) =>
      val topicLists = (0 until nodesCount).map { i =>
        val nodeIndex = NodeIndex(i, nodesCount)
        (i, MessageGenerator.thisNodeGroupIndexes(topicGroupsPerNode, nodeIndex), MessageGenerator.thisNodeTopics(topicPrefix, topicGroupsPerNode, topicsPerNode, nodeIndex))
      }

      val allNodesTopics = topicLists.map(_._3).flatten.toSet
      allNodesTopics.size shouldBe topicsPerNode*nodesCount
      val allNodesGroupsFromTopics = topicLists.map(_._3).flatten.map(_.split("/").apply(1).toInt).toSet
      val allNodesGroups = topicLists.map(_._2).flatten.toSet
      allNodesGroups.size shouldBe topicGroupsPerNode*nodesCount
      allNodesGroupsFromTopics shouldBe allNodesGroups

      for((i, groups, topics) <- topicLists) {
        withClue(s"Node ${i} properties") {
          topics.toSet.size shouldBe topicsPerNode
          groups.size shouldBe topicGroupsPerNode
          topics.map(_.split("/").apply(1).toInt).toSet shouldBe groups.toSet
        }
      }
    }
  }

  it should "generate GroupedTopics for the node" in {
    val topicPrefix = "foo"
    forAll(Table(
      ("topicGroupsPerNode", "topicsPerNode", "nodesCount"),
      (1, 10, 1),
      (1, 10, 3),
      (10, 100, 1),
      (10, 100, 3)
    )) {(topicGroupsPerNode: Int, topicsPerNode: Int, nodesCount: Int) =>
      val nodeToTopics = (0 until nodesCount).map { i =>
        val nodeIndex = NodeIndex(i, nodesCount)
        (i, MessageGenerator.thisNodeGroupedTopics(topicPrefix, topicGroupsPerNode, topicsPerNode, nodeIndex))
      }

      val allNodesTopics = nodeToTopics.flatMap(_._2.topicsByGroupId.values.flatten).toSet
      allNodesTopics.size shouldBe topicsPerNode*nodesCount
      val allNodesGroupsFromTopics = allNodesTopics.map(_.value.split("/").apply(1).toInt).toSet
      val allNodesGroups = nodeToTopics.flatMap(_._2.topicsByGroupId.keys).toSet
      allNodesGroups.size shouldBe topicGroupsPerNode*nodesCount
      allNodesGroupsFromTopics shouldBe allNodesGroups

      for((i, groupedTopics) <- nodeToTopics) {
        withClue(s"Node ${i} properties") {
          groupedTopics.topicsByGroupId.values.flatten.toSet.size shouldBe topicsPerNode
          groupedTopics.topicsByGroupId.keys.size shouldBe topicGroupsPerNode
          groupedTopics.topicsByGroupId.flatMap((groupId, topics) => topics.map(groupId -> _)).foreach { (groupId, topic) =>
            withClue(s"Node ${i}, topic ${topic} group") {
              topic.value.split("/").apply(1).toInt shouldBe groupId
            }
          }
        }
      }
    }
  }
}
