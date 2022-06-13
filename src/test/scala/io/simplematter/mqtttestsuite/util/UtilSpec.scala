package io.simplematter.mqtttestsuite.util

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class UtilSpec extends AnyFlatSpec
  with should.Matchers
  with ScalaCheckPropertyChecks
  with TableDrivenPropertyChecks {

  "pickCircular" should "pick items from the Seq" in {
    forAll(Table(
      ("Items Count", "Slice size", "Slices count"),
      (1, 1, 1),
      (1, 1, 2),
      (2, 2, 2),
      (9, 1, 5),
      (9, 2, 5),
      (9, 3, 5),
      (9, 4, 5),
      (10, 1, 1),
      (10, 1, 5),
      (10, 2, 5),
      (10, 1, 10),
      (10, 1, 20),
      (10, 2, 10),
      (10, 2, 20),
    )) {(itemsCount: Int, sliceSize: Int, slicesCount: Int ) =>
      val items = 1 to itemsCount
      val slices = (0 until slicesCount).map { sliceIndex =>
        val slice = pickCircular(items, sliceSize, sliceIndex)
        withClue(s"Slice ${sliceIndex}") {
          slice.size shouldBe sliceSize
          slice.toSet.size shouldBe sliceSize
        }
        slice
      }
      val allSlicesSeq = slices.flatten
      val allSlicesItemsCounts: Map[Int, Int] = allSlicesSeq.groupBy(i => i).view.mapValues(_.size).toMap
      allSlicesItemsCounts.size should be <= itemsCount
      val maxRepetitions = Math.ceil( (sliceSize * slicesCount).toDouble / itemsCount).toInt
      allSlicesItemsCounts.foreach { (item, repetitions) =>
        withClue(s"Item ${item} repetitions") {
          repetitions should be <= maxRepetitions
        }
      }
    }
  }

  "pickCircular" should "return empty seq for special cases" in {
    forAll(Table(
      ("Items Count", "Slice size", "Slice index"),
      (1, 0, 0),
      (1, 0, 1),
      (1, 0, 2),
      (10, 0, 0),
      (10, 0, 5),
      (10, 0, 10),
      (0, 1, 0),
      (0, 1, 5),
      (0, 1, 10),
      (0, 2, 0),
      (0, 2, 5),
      (0, 10, 0),
      (0, 10, 10),
    )) { (itemsCount: Int, sliceSize: Int, sliceIndex: Int) =>
      val items = 1 to itemsCount
      val slice = pickCircular(items, sliceSize, sliceIndex)
      slice shouldBe empty
    }
  }
}
