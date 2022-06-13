package io.simplematter.mqtttestsuite.model

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should

class NodeIndexSpec extends AnyFlatSpec
  with should.Matchers {
  "NodeIndex" should "pick the items" in {
    NodeIndex(0, 2).pick(Seq("a", "b", "c", "d")) shouldBe Seq("a", "b")
    NodeIndex(1, 2).pick(Seq("a", "b", "c", "d")) shouldBe Seq("c", "d")
    NodeIndex(2, 2).pick(Seq("a", "b", "c", "d")) shouldBe empty
    NodeIndex(0, 2).pick(Seq("a", "b", "c")) shouldBe Seq("a", "b")
    NodeIndex(1, 2).pick(Seq("a", "b", "c")) shouldBe Seq("c")
    NodeIndex(2, 2).pick(Seq("a", "b", "c")) shouldBe empty
  }
}
