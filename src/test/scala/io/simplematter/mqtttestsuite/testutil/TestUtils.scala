package io.simplematter.mqtttestsuite.testutil

import java.net.ServerSocket

object TestUtils {
  def findFreePorts(n: Int): Seq[Int] = {
    (for (i <- 0 until n)
      yield {
        val s = new ServerSocket(0)
        s.setReuseAddress(true)
        (s.getLocalPort, s)
      }).
      map({ case (p, s) => s.close; p; })
  }
}
