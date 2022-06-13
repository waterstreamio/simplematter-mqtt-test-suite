package io.simplematter.mqtttestsuite.util

import org.scalacheck.Gen
import org.scalacheck.Prop.forAllNoShrink
import org.scalactic.TolerantNumerics
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import zio.Exit

import scala.util.Random

class QuantileApproxSpec extends AnyFlatSpec
  with should.Matchers
  with ScalaCheckPropertyChecks
  with TableDrivenPropertyChecks
  {

  private val validStringLength = Gen.choose(0, 1000000)

  private val firstBucketGen = Gen.choose(1, 10)
  private val nextBucketPercentGen = Gen.choose(10, 100)
  private val quantileGen = Gen.choose(0.0, 1.0)
  private val samplesGen = Gen.choose(0, 100).flatMap(l => Gen.listOfN(l, Gen.choose(0L, Int.MaxValue.toLong)))

  "QuantileApprox" should "calculate quantiles with 1, 50" in {
    val firstBucket = 1
    val nextBucketPercent = 50
    val testData = Table(
      ("Samples", "Quantile", "Expected value min", "Expected value max"),
      (Seq(), 0.5, 0, 0),
      (Seq(0), 0.5, 0, 1),
      (Seq(1,2,3,4), 0.5, 2, 2),
      (Seq(1,2,3,4,5,6,7,8,9,10), 0.5, 5, 5),
      (Seq(1,2,3,4,5,6,7,8,9,10), 0.9, 9, 10),
      (Seq(100, 1000, 200, 500, 300, 700), 0.5, 300, 500),
      (Seq(100, 1000, 200, 500, 300, 700, 800, 550, 750, 780, 810), 0.5, 550, 700),
      (Seq(10000, 1, 10, 1000, 50, 200, 100, 200, 150, 220, 12, 15, 20, 50, 60, 10, 15, 25, 30, 25), 0.5, 30, 50),
      (Seq(10000, 1, 10, 1000, 50, 200, 100, 200, 150, 220, 12, 15, 20, 50, 60, 10, 15, 25, 30, 25), 0.75, 150, 200),
      ((Seq.fill(10)(10) ++ Seq.fill(10)(100) ++ Seq.fill(10)(1000) ++ Seq.fill(10)(10000)), 0.5, 100, 150),
      ((Seq.fill(10)(10) ++ Seq.fill(10)(100) ++ Seq.fill(10)(1000) ++ Seq.fill(10)(10000)), 0.75, 1000, 1500),
      ((Seq.fill(10)(10) ++ Seq.fill(10)(100) ++ Seq.fill(10)(1000) ++ Seq.fill(10)(10000)), 0.90, 9000, 10000)
    )
    forAll(testData) { (samples: Seq[Int], quantile: Double, expectedValueMin: Int, expectedValueMax: Int) =>
      val q = new QuantileApprox(firstBucket, nextBucketPercent)
      samples.foreach(s => q.add(s.toLong))
      val actualValue = q.quantile(quantile)
      actualValue should be >= expectedValueMin.toLong
      actualValue should be <= expectedValueMax.toLong
    }
  }

  it should "calculate quantiles with 1, 10" in {
    val firstBucket = 1
    val nextBucketPercent = 10
    val testData = Table(
      ("Samples", "Quantile", "Expected value min", "Expected value max"),
      (Seq(), 0.5, 0, 0),
      (Seq(0), 0.5, 0, 1),
      (Seq(0), 0.01, 0, 1),
      (Seq.fill(100)(0), 0.01, 0, 1),
      (Seq(1,2,3,4), 0.5, 2, 2),
      (Seq(1,2,3,4,5,6,7,8,9,10), 0.5, 5, 5),
      (Seq(1,2,3,4,5,6,7,8,9,10), 0.9, 9, 10),
      (Seq(100, 1000, 200, 500, 300, 700), 0.5, 300, 500),
      (Seq(100, 1000, 200, 500, 300, 700, 800, 550, 750, 780, 810), 0.5, 550, 700),
      (Seq(10000, 1, 10, 1000, 50, 200, 100, 200, 150, 220, 12, 15, 20, 50, 60, 10, 15, 25, 30, 25), 0.5, 30, 50),
      (Seq(10000, 1, 10, 1000, 50, 200, 100, 200, 150, 220, 12, 15, 20, 50, 60, 10, 15, 25, 30, 25), 0.75, 150, 200),
      ((Seq.fill(10)(10) ++ Seq.fill(10)(100) ++ Seq.fill(10)(1000) ++ Seq.fill(10)(10000)), 0.5, 100, 110),
      ((Seq.fill(10)(10) ++ Seq.fill(10)(100) ++ Seq.fill(10)(1000) ++ Seq.fill(10)(10000)), 0.75, 1000, 1100),
      ((Seq.fill(10)(10) ++ Seq.fill(10)(100) ++ Seq.fill(10)(1000) ++ Seq.fill(10)(10000)), 0.90, 9500, 10000)
    )
    forAll(testData) { (samples: Seq[Int], quantile: Double, expectedValueMin: Int, expectedValueMax: Int) =>
      val q = new QuantileApprox(firstBucket, nextBucketPercent)
      samples.foreach(s => q.add(s.toLong))
      val actualValue = q.quantile(quantile)
      actualValue should be >= expectedValueMin.toLong
      actualValue should be <= expectedValueMax.toLong
    }
  }

  it should "calculate quantiles - random evenly distributed data" in {
    val maxValue = 100000L
    //To avoid shrinking and speed up tests use the pre-generated table rather than Gen
    val testData = Table(
      ("Samples"),
      Seq.fill(10)((Seq.fill(10000)(Random.nextLong(maxValue)))): _*
    )
    val quantileLevels = Seq(0.5, 0.75, 0.9, 0.95, 0.99)
    val firstBucket = 1
    val nextBucketPercent = 10
    val expectedAccuracy = 0.05
    forAll(testData) { (samples) =>
      val q = new QuantileApprox(firstBucket, nextBucketPercent)
      samples.foreach(q.add)
      for (ql <- quantileLevels) {
        withClue(s"quantile ${ql}") {
          val expectedValue = (maxValue * ql).toLong
          val tolerance = Math.max(10L, (expectedValue*expectedAccuracy).toLong)
          q.quantile(ql) shouldBe expectedValue +- tolerance
        }
      }
    }
  }

  it should "restore from the snapshot" in {
    val firstBucket = 1
    val nextBucketPercent = 10
    val testData = Table(
      ("Provided buckets"),
      (Seq()),
      (Seq(0, 1)),
      (Seq(1,2,3)),
      (Seq(1,0,0,2,3)),
      (Seq(2,3,2,3,2,3))
    )
    forAll(testData) { (providedBuckets: Seq[Int]) =>
      val q = new QuantileApprox(firstBucket, nextBucketPercent, initialData = QuantileApprox.Snapshot(providedBuckets))
      val derivedSnapshot = q.snapshot()
      derivedSnapshot.buckets shouldBe providedBuckets
    }
  }

  it should "produce same result from the snapshot" in {
    forAll((firstBucketGen, "firstBucket"), (nextBucketPercentGen, "nextBucket"), (quantileGen, "quantile"), (samplesGen, "samples")) {
      (firstBucket, nextBucketPercent, quantile, samples) =>
        whenever(firstBucket > 0 && nextBucketPercent >= 10 && quantile >= 0.0 && samples.size <= 100) {
          val q = QuantileApprox(firstBucket, nextBucketPercent)
          samples.foreach(q.add)
          val snapshot = q.snapshot()
          val restoredQ = QuantileApprox(firstBucket, nextBucketPercent, initialData = snapshot)
          restoredQ.quantile(quantile) shouldBe q.quantile(quantile)
        }
    }
  }

  it should "aggregate data from multiple snapshots" in {
    forAll((firstBucketGen, "firstBucket"), (nextBucketPercentGen, "nextBucket"), (quantileGen, "quantile"), (samplesGen, "samples1"), (samplesGen, "samples2")) {
      (firstBucket, nextBucketPercent, quantile, samples1, samples2) =>
        whenever(firstBucket > 0 && nextBucketPercent >= 10 && quantile >= 0.0 && samples1.size <= 100 && samples2.size <= 100) {
          val q1 = QuantileApprox(firstBucket, nextBucketPercent)
          samples1.foreach(q1.add)
          val q2 = QuantileApprox(firstBucket, nextBucketPercent)
          samples2.foreach(q2.add)

          val qAgg = QuantileApprox(firstBucket, nextBucketPercent)
          qAgg.aggregate(q1.snapshot())
          qAgg.aggregate(q2.snapshot())

          val qCommon = QuantileApprox(firstBucket, nextBucketPercent)
          samples1.foreach(qCommon.add)
          samples2.foreach(qCommon.add)

          qAgg.snapshot() shouldBe qCommon.snapshot()
          qAgg.quantile(quantile) shouldBe qCommon.quantile(quantile)
        }
    }
  }


}
