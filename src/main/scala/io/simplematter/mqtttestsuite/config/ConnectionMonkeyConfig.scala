package io.simplematter.mqtttestsuite.config

import zio.json.{DeriveJsonDecoder, DeriveJsonEncoder, JsonDecoder, JsonEncoder}

import zio.Duration
import scala.util.Random

case class ConnectionMonkeyConfig(intermittentClientsPercentage: Int = 0,
                                  averageClientUptimeSeconds: Int = 120,
                                  averageClientDowntimeSeconds: Int = 30,
                                  timeSdSeconds: Int = 10) {
  def uptimeDuration: Duration = Duration.fromSeconds(Math.max(1, Random.nextGaussian() * timeSdSeconds + averageClientUptimeSeconds).toInt)

  def downtimeDuration: Duration = Duration.fromSeconds(Math.max(0, Random.nextGaussian() * timeSdSeconds + averageClientDowntimeSeconds).toInt)
}

object ConnectionMonkeyConfig {
  val disabled = ConnectionMonkeyConfig(intermittentClientsPercentage = 0)

  implicit val encoder: JsonEncoder[ConnectionMonkeyConfig] = DeriveJsonEncoder.gen[ConnectionMonkeyConfig]
  implicit val decoder: JsonDecoder[ConnectionMonkeyConfig] = DeriveJsonDecoder.gen[ConnectionMonkeyConfig]
}
