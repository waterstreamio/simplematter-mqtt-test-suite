package io.simplematter.mqtttestsuite.util

import io.simplematter.mqtttestsuite.util.QuantileApprox.Snapshot
import org.slf4j.LoggerFactory

import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ListBuffer

class QuantileApprox(firstBucket: Long = 1,
                     nextBucketPercent: Int = 50,
                     maxValue: Long = Int.MaxValue.toLong /*55 buckets to handle it*/,
                     bucketsLimit: Int = 50000,
                     initialData: QuantileApprox.Snapshot = QuantileApprox.Snapshot.empty) {
  import QuantileApprox.log

  {
    //validate params
    if(firstBucket < 1)
      throw IllegalArgumentException("firstBucket must be 1 or greater")
    if(nextBucketPercent < 1 || nextBucketPercent > 200)
      throw IllegalArgumentException("nextBucketPercent must be between 1 and 200")
  }

  private val bucketMultiplier = 1.0 + nextBucketPercent.toDouble / 100
  private val bucketMLog = Math.log(bucketMultiplier)
  private val buckets = {
    val bucketsNumber = (Math.log(maxValue.toDouble / firstBucket)/bucketMLog).toInt + 1
    if(bucketsNumber > bucketsLimit)
      throw IllegalArgumentException(s"Too many buckets needed: $bucketsNumber. Increase firstBucket, nextBucketPercent or reduce maxValue.")
    AtomicIntegerArray(bucketsNumber)
  }
  private val totalSamples = AtomicInteger(0)

  {
    //Set up the initial data from the snapshot
    aggregate(initialData)
  }


  private def bucketByValue(v: Long): Int = {
    val b = Math.ceil(Math.log(v.toDouble / firstBucket)/bucketMLog).toInt
    if(b < 0)
      0
    else if (b > buckets.length() - 1)
      buckets.length() - 1
    else
      b
  }

  def add(v: Long): Unit = {
    val b = bucketByValue(v)
    buckets.incrementAndGet(b)
    totalSamples.incrementAndGet()
  }

  private def bucketLowerBound(bucket: Int): Double = {
    if (bucket <= 0) 0 else firstBucket * Math.pow(bucketMultiplier, bucket - 1)
  }

  def quantile(q: Double): Long = {
    val samplesRequired = (totalSamples.get() * q).toInt
    var samplesAccumulated = 0
    var i = 0
    while(samplesAccumulated < samplesRequired && i < buckets.length()) {
      samplesAccumulated += buckets.get(i)
      i += 1
    }
    if(samplesAccumulated == 0) {
      0L
    } else {
      val bucketLower = bucketLowerBound(i - 1)
      val bucketUpper: Double = if(i <= 1) firstBucket.toDouble else bucketLower * bucketMultiplier
      val bucketRange = bucketUpper - bucketLower
      val currentBucketSamples: Int = buckets.get(i - 1)
      val currentBucketRequiredSamples: Int = samplesRequired - (samplesAccumulated - currentBucketSamples)
      val interpolatedValue = (currentBucketRequiredSamples.toDouble / currentBucketSamples.toDouble) * bucketRange + bucketLower
      if(log.isTraceEnabled)
        log.trace(s"quantile(${q}): sr=$samplesRequired, sa=${samplesAccumulated} i=${i}, bucketLower=${bucketLower}, bucketUpper=${bucketUpper}, interpolated=${interpolatedValue} buckets=${buckets}")
      interpolatedValue.toLong
    }
  }

  /**
   * !!! Non-atomic !!!
   * @return
   */
  def snapshot(): QuantileApprox.Snapshot = {
    val lb = ListBuffer.empty[Int]
    for(i <- 0 until buckets.length()) {
      lb.addOne(buckets.get(i))
    }
    val nonZeroEnd = lb.lastIndexWhere(_ != 0)
    if(nonZeroEnd < 0)
      Snapshot(Nil)
    else
      Snapshot(lb.takeInPlace(nonZeroEnd + 1).toList)
  }

  def aggregate(externalSource: QuantileApprox.Snapshot): Unit = {
    externalSource.buckets.zipWithIndex.take(buckets.length()).foreach {(v, i) =>
      buckets.addAndGet(i, v)
      totalSamples.addAndGet(v)
    }
  }
}

object QuantileApprox {
  private val log = LoggerFactory.getLogger(QuantileApprox.getClass)

  case class Snapshot(buckets: Seq[Int]) {}

  object Snapshot {
    val empty = Snapshot(Nil)
  }
}
