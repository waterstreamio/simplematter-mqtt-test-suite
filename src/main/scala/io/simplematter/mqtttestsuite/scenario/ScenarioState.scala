package io.simplematter.mqtttestsuite.scenario

import io.simplematter.mqtttestsuite.scenario
import zio.json.JsonEncoder
import zio.json.JsonDecoder

enum ScenarioState(val endState: Boolean, val active: Boolean) {
  case New extends ScenarioState(false, false)
  case RampUp extends ScenarioState(false, false)
  case Running extends ScenarioState(false, true)
  case Done extends ScenarioState(true, false)
  case Fail extends ScenarioState(true, false)

  def agg(other: ScenarioState): ScenarioState = {
    if(this == Fail || other == Fail)
      Fail
    else if(this == Done && other == Done)
      Done
    else if(this == Running || other == Running)
      Running
    else if(this == RampUp || other == RampUp)
      RampUp
    else
      New
  }
}

object ScenarioState {
  implicit val encoder: JsonEncoder[ScenarioState] = JsonEncoder[String].contramap(_.toString)

  implicit val decoder: JsonDecoder[ScenarioState] = JsonDecoder[String].map(ScenarioState.valueOf(_))
}

