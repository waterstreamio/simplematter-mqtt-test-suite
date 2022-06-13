package io.simplematter.mqtttestsuite.model

import zio.json.{JsonDecoder, JsonEncoder}

object JsonCodecs {
  //Not in a companion object because as for Scala 3.1 JsonEncoder.contramap fails at runtime during NodeId instantiation if placed in the NodeId companion object
  implicit val encoder: JsonEncoder[NodeId] = JsonEncoder[String].contramap(_.toString)

  implicit val decoder: JsonDecoder[NodeId] = JsonDecoder[String].map(NodeId.apply)
}
