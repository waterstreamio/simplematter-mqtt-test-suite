package io.simplematter.mqtttestsuite.model

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class GroupedTopicsSpec extends AnyFlatSpec
  with should.Matchers
  with ScalaCheckPropertyChecks
  with TableDrivenPropertyChecks {

  "GroupedTopics" should "slice" in {
    val topicPrefix = "foo"
    forAll(Table(
      ("Groups count", "Topics per group", "Groups slice size", "Topics slice size", "Slices count"),
      (1, 1, 1, 1, 1),
      (1, 1, 1, 1, 2),
      (1, 1, 0, 1, 1),
      (1, 1, 1, 0, 1),
      (1, 10, 1, 1, 1),
      (1, 10, 1, 1, 10),
      (1, 10, 0, 1, 10),
      (1, 10, 1, 0, 10),
      (1, 10, 0, 2, 10),
      (5, 10, 1, 1, 10),
      (5, 10, 0, 1, 10),
      (5, 10, 1, 0, 10),
    )) { (groupsCount, topicsPerGroup, groupsSliceSize, topicsSliceSize, slicesCount) =>
      val topicsByGroupId = (1 to groupsCount).map { g =>
        g -> (1 to topicsPerGroup).map { t => MqttTopicName(s"$topicPrefix/$g/${t + (g - 1)*topicsPerGroup}") }.toSeq
      }.toMap
      val groupedTopics = GroupedTopics(topicsByGroupId, topicPrefix)

      val slices = (0 until slicesCount).map { sliceIndex =>
        val slice = groupedTopics.circularSlice(groupsSliceSize, topicsSliceSize, sliceIndex)
        withClue(s"Slice ${sliceIndex}") {
          slice.groupPatterns.size shouldBe groupsSliceSize
          slice.assignedTopics.size shouldBe topicsSliceSize
          slice.topicsForGroups.size shouldBe groupsSliceSize*topicsPerGroup
        }
        slice
      }

      val allPickedGroups = slices.flatMap(_.groupPatterns)
      allPickedGroups.size shouldBe groupsSliceSize*slicesCount
      allPickedGroups.toSet.size should be <= groupsCount

      val allAssignedTopics = slices.flatMap(_.assignedTopics)
      allAssignedTopics.size shouldBe topicsSliceSize*slicesCount
      allAssignedTopics.toSet.size should be <= groupsCount*topicsPerGroup
    }
  }

}
