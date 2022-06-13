package io.simplematter.mqtttestsuite

import org.slf4j.Logger
import zio.Schedule.StepFunction

import scala.concurrent.{ExecutionContext, Future}
import zio.{RIO, Schedule, URIO, ZIO}
import zio.duration.*

import java.time.OffsetDateTime

package object util {
  extension (f: Future[_]) {
    def logExceptions(message: String, log: Logger)(implicit ec: ExecutionContext): Unit = {
      f.recoverWith { case e: Exception =>
        log.error(message, e)
        Future.failed(e)
      }
    }
  }

  extension [R, A](r: RIO[R, A]) {
    def logExceptions(message: String, log: Logger, default: A): URIO[R, A] = {
      r.catchAll { e =>
        log.error(message, e)
        URIO.succeed(default)
      }
    }
  }

  def minNonZero(a: Long, b: Long): Long = {
    if (a == 0L)
      b
    else if(b == 0L)
      a
    else
      Math.min(a, b)
  }

  /**
   * Prepend items from first to second, returning up to maxSize items.
   * Items from `second` taken only if size of `first` is smaller than maxSize
   *
   * @param first
   * @param second
   * @param maxSize
   * @tparam T
   * @return
   */
  def concatBeginningUpTo[T](first: Seq[T], second: Seq[T], maxSize: Int): Seq[T] =
    if(first.isEmpty)
      second.take(maxSize)
    else if(second.isEmpty)
      first.take(maxSize)
    else if(first.size >= maxSize)
      first.take(maxSize)
    else
      first ++ second.take(maxSize - first.size)

  def concatEndUpTo[T](first: Seq[T], second: Seq[T], maxSize: Int): Seq[T] =
    if(first.isEmpty)
      second.takeRight(maxSize)
    else if(second.isEmpty)
      first.takeRight(maxSize)
    else if(second.size >= maxSize)
      second.takeRight(maxSize)
    else
      first.takeRight(maxSize - second.size) ++ second

  def singleParamMap(k: String, v: Option[String]): Map[String, String] =
    v.filter(_.nonEmpty).fold(Map[String, String]())(v => Map(k -> v))

  /**
   * Example: from (1,2,3,4,5) picks following slices of size 2: 0 -> (1,2), 1 -> (3,4), 2 -> (5,1), 3 -> (2,3), etc
   *
   * @param items items to pick from
   * @param sliceSize size of the slice. Should be <= items.size
   * @param sliceIndex
   * @tparam A
   * @return
   */
  def pickCircular[A](items: Iterable[A], sliceSize: Int, sliceIndex: Int): Seq[A] = {
    if(sliceSize == 0 || items.isEmpty) {
      Seq.empty
    } else if(items.size == sliceSize) {
        items.toSeq
    } else {
      val startIndex = sliceSize * sliceIndex % items.size
      val untilIndex = sliceSize * (sliceIndex + 1) % items.size
      if (startIndex <= untilIndex)
        items.slice(startIndex, untilIndex).toSeq
      else
        (items.slice(startIndex, items.size) ++ items.take(untilIndex)).toSeq
    }
  }

  def scheduleFrequency(timesPerSecond: Double): Schedule[Any, Any, Long] = {
    import zio.Schedule.Decision._
    import java.time.Duration

    final case class State(start: OffsetDateTime)

    def loop(state: Option[State], n: Long): StepFunction[Any, Any, Long] =
      (now: OffsetDateTime, _: Any) =>
        ZIO.succeed(state match {
          case Some(State(start)) =>
            val nowMillis     = now.toInstant.toEpochMilli()
            val startMillis   = start.toInstant.toEpochMilli()
            val expectedDurationMillis = (n*1000 / timesPerSecond).toLong
            val runningBehind = expectedDurationMillis < nowMillis - startMillis
            val sleepTime = (nowMillis - startMillis)
            val nextRun   = if (runningBehind) now else start.plus(Duration.ofMillis(expectedDurationMillis))

            Continue(
              n + 1L,
              nextRun,
                loop(Some(State(start)), n + 1L)
            )
          case None =>
            val nextRun   = now.plus(Duration.ofMillis((1000/timesPerSecond).toLong))

            Continue(
              n + 1L,
              nextRun,
                loop(Some(State(now)), n + 1L)
            )
        })

    Schedule(loop(None, 0L))
  }

}
