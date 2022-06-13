package io.simplematter.mqtttestsuite.testutil

import org.testcontainers.containers.{BindMode, GenericContainer}

import java.io.File
import java.nio.file.Files

object ContainerUtils {
  def mosquittoContainer(mosquittoVersion: String = "2.0.11"): GenericContainer[_] = {
    val mosquittoConfigFile = File.createTempFile("mosquitto", ".conf")
    Files.writeString(mosquittoConfigFile.toPath,
      s"""
         |allow_anonymous true
         |listener 1883 0.0.0.0
         |log_dest stdout
         |log_timestamp_format %Y-%m-%dT%H:%M:%S
         |""".stripMargin)
    mosquittoConfigFile.deleteOnExit()
    val c = new GenericContainer(s"eclipse-mosquitto:${mosquittoVersion}")
    c.withExposedPorts(1883)
    c.withFileSystemBind(mosquittoConfigFile.getAbsolutePath, "/mosquitto/config/mosquitto.conf", BindMode.READ_ONLY)
    c.waitingFor(org.testcontainers.containers.wait.strategy.Wait.forListeningPort())
    c
  }


}
