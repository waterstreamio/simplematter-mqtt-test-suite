package io.simplematter.mqtttestsuite.config

import zio.json.{DeriveJsonDecoder, DeriveJsonEncoder, JsonDecoder, JsonEncoder}

import zio.duration._
import scala.util.Random

case class ConnectionMonkeyConfig(intermittentClientsPercentage: Int = 0,
                                  averageClientUptimeSeconds: Int = 120,
                                  averageClientDowntimeSeconds: Int = 30,
                                  timeSdSeconds: Int = 10) {
  def uptimeDuration: Duration = Math.max(1, Random.nextGaussian() * timeSdSeconds + averageClientUptimeSeconds).toInt.seconds

  def downtimeDuration: Duration = Math.max(0, Random.nextGaussian() * timeSdSeconds + averageClientDowntimeSeconds).toInt.seconds
}

object ConnectionMonkeyConfig {
  val disabled = ConnectionMonkeyConfig(intermittentClientsPercentage = 0)

  implicit val encoder: JsonEncoder[ConnectionMonkeyConfig] = DeriveJsonEncoder.gen[ConnectionMonkeyConfig]
  implicit val decoder: JsonDecoder[ConnectionMonkeyConfig] = DeriveJsonDecoder.gen[ConnectionMonkeyConfig]
}
