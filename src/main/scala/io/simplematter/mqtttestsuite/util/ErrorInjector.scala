package io.simplematter.mqtttestsuite.util

import io.simplematter.mqtttestsuite.config.ErrorInjectionConfig
import zio.{RIO, Task, ZIO}

import scala.util.Random

/**
 * Injects the errors in order to test how the MQTT broker errors would be handled.
 */
class ErrorInjector(errorInjectionConfig: ErrorInjectionConfig) {
  def sendMessage(effect: Task[Unit]): Task[Unit] = {
    if(Random.nextInt(100) < errorInjectionConfig.publishDuplicatePercentage) {
      effect.flatMap(_ => effect)
    } else if (Random.nextInt(100) < errorInjectionConfig.publishMissedPercentage) {
      ZIO.succeed(())
    } else {
      effect
    }
  }

  def receiveMessage[R](effect: RIO[R, Unit]): RIO[R, Unit] = {
    if(Random.nextInt(100) < errorInjectionConfig.receiveDuplicatePercentage) {
      effect.flatMap(_ => effect)
    } else if (Random.nextInt(100) < errorInjectionConfig.receiveMissedPercentage) {
      ZIO.succeed(())
    } else {
      effect
    }
  }
}
