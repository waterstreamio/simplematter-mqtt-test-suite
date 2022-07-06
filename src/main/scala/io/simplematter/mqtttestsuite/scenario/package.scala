package io.simplematter.mqtttestsuite

import com.hazelcast.core.HazelcastInstance
import io.simplematter.mqtttestsuite.stats.FlightRecorder
import zio.Clock

package object scenario {
//  type ScenarioEnv = Clock with Blocking with Has[FlightRecorder]
  type ScenarioEnv = Clock with FlightRecorder with HazelcastInstance

}
