package io.simplematter.mqtttestsuite

import com.hazelcast.core.HazelcastInstance
import io.simplematter.mqtttestsuite.stats.FlightRecorder
import zio.Has
import zio.clock.Clock
import zio.blocking.Blocking

package object scenario {
//  type ScenarioEnv = Clock with Blocking with Has[FlightRecorder]
  type ScenarioEnv = Clock with Blocking with Has[FlightRecorder] with Has[HazelcastInstance]

}
