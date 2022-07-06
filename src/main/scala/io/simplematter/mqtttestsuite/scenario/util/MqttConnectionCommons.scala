package io.simplematter.mqtttestsuite.scenario.util

import io.netty.handler.codec.mqtt.MqttQoS
import io.simplematter.mqtttestsuite.config.{ConnectionMonkeyConfig, MqttBrokerConfig}
import io.simplematter.mqtttestsuite.model.ClientId
import io.simplematter.mqtttestsuite.mqtt.{MqttClient, MqttOptions}
import io.simplematter.mqtttestsuite.scenario.util.MqttPublisher.log
import io.simplematter.mqtttestsuite.stats.FlightRecorder
import org.slf4j.LoggerFactory
import zio.{Promise, RIO, Schedule, Semaphore, UIO, URIO, ZIO}
import zio.Clock
import zio.Duration

import scala.jdk.CollectionConverters.*
import java.util.concurrent.ConcurrentSkipListSet
import scala.concurrent.TimeoutException

class MqttConnectionCommons protected (mqttBrokerConfig: MqttBrokerConfig,
                                       connectionMonkey: ConnectionMonkeyConfig,
                                       clientId: ClientId,
                                       intermittent: Boolean,
                                       cleanSession: Boolean,
                                       qos: MqttQoS,
                                       flightRecorder: FlightRecorder,
                                       clientConnectMutex: Semaphore
                                       ) {
  import MqttConnectionCommons.log

  //TODO can we wrap the code into the main runtime and avoid unsafe env here?
  private val unsafeRuntime = zio.Runtime.default.withEnvironment(zio.DefaultServices.live).unsafe

  protected val client = new MqttClient(mqttBrokerConfig.serverHost,
    mqttBrokerConfig.serverPort,
    MqttOptions(
      clientId = Option(clientId),
      connectTimeoutMillis = mqttBrokerConfig.connectionTimeoutSeconds * 1000,
      keepAliveTimeSeconds = mqttBrokerConfig.keepAliveSeconds,
      maxMessageSize = mqttBrokerConfig.messageMaxSize*2 /* give additional space for messages that slightly exceed the max size */,
      maxRetransmissionAttempts = mqttBrokerConfig.maxRetransmissionAttempts
    ))

  {
    client.onClose { wasAccepted =>
      zio.Unsafe.unsafe {
        unsafeRuntime.run(flightRecorder.mqttConnectionClosed(clientId, wasAccepted))
      }
    }
    client.onPublishRetransmit { publishMsg =>
      zio.Unsafe.unsafe {
        unsafeRuntime.run(flightRecorder.mqttPublishMessageRetransmitAttempt())
      }
    }
    client.onPubrelRetransmit { msgId =>
      zio.Unsafe.unsafe {
        unsafeRuntime.run(flightRecorder.mqttPubrelMessageRetransmitAttempt())
      }
    }
  }


  /**
   * Establish connection to the MQTT broker.
   * Doesn't re-try in case of failure.
   *
   * @return
   */
  val connectIfNotConnected: RIO[Clock, Unit] = {
    clientConnectMutex.withPermit(
      ZIO.attempt { client.isConnected() }.flatMap(isConnected =>
        if(isConnected) {
          ZIO.succeed(())
        } else {
            ZIO.attempt { log.info("Client {} not connected, connecting it", clientId) }.flatMap { _ =>
            flightRecorder.recordMqttConnect(clientId,
              //Not interrupt so that we have an accurate stats for the clients that were trying to connect when the test was terminating
              ZIO.fromFuture { implicit ec => client.connect(cleanSession) })
              .map { _ =>
                log.debug(s"Client ${clientId} connection established")
              }.uninterruptible.flatMap(_ => onConnect)
          }
        }
      )
    )
  }

  /**
   * Effect that runs upon connection establishing
   */
  protected val onConnect: URIO[Clock, Unit] = ZIO.succeed(())

  /**
   * Initiate client disconnect and wait until it is actually disconnected or `timeout` passes
   *
   * @param timeout
   * @return
   */
  def disconnect(timeout: Duration = Duration.fromSeconds(30)): RIO[Clock, Unit] = {
    clientConnectMutex.withPermit( ZIO.attempt { client.isConnected() }.flatMap(isConnected =>
      if(isConnected) {
        ZIO.fromFuture { implicit ec => client.disconnect() } .flatMap { _ =>
          ZIO.succeed(client.isConnected()).repeat(Schedule.recurUntilEquals(false) && Schedule.spaced(Duration.fromMillis(100)))
            .timeoutFail(throw new TimeoutException(s"MQTT client disconnect timed out after ${timeout}"))(timeout)
        }.as(())
      } else {
        ZIO.succeed(())
      }
    ))
  }

  /**
   * "Ungracefully" drop client connection to simulate the network issue
   *
   * @return
   */
  private def dropConnection: RIO[Clock, Unit] = {
        ZIO.fromFuture { implicit ec => client.dropConnection() }.
          map(_ => log.debug("Client {} connection dropped", clientId)).unit
  }

  private def connectionOnPhase(statusCheckInterval: Duration = Duration.fromSeconds(1)): RIO[Clock, Unit] = {
    for {
      uptimeDuration <- ZIO.attempt { connectionMonkey.uptimeDuration }
      _ = log.debug("Client {} {} connection uptime phase starts", clientId, uptimeDuration)
      _ <- flightRecorder.scheduledUptime(clientId)
      //TODO catch the errors to prevent shutting down the loop
      _ <- connectIfNotConnected.repeat(Schedule.spaced(statusCheckInterval).upTo(uptimeDuration))
    } yield ()
  }

  private val connectionOffPhase: RIO[Clock, Unit] = {
    for {
      downtimeDuration <- ZIO.attempt { connectionMonkey.downtimeDuration }
      _ = log.debug("Client {} {} connection downtime phase starts", clientId, downtimeDuration)
      _ <- flightRecorder.scheduledDowntime(clientId)
      _ <- dropConnection.flatMap(_ => ZIO.unit.delay(downtimeDuration))
    } yield ()
  }

  private def connectionMaintenanceIteration(finalizing: Promise[Nothing, Unit], statusCheckInterval: Duration = Duration.fromSeconds(1)): RIO[Clock, Unit] =
    if(intermittent) {
      log.debug("Tracking {} as intermittent: {}", clientId, connectionMonkey)
//      connectionOnPhase(statusCheckInterval) *> connectionOffPhase
      for {
        _ <- (connectionOnPhase(statusCheckInterval) *> connectionOffPhase).raceFirst(finalizing.await)
        f <- finalizing.isDone
        _ <- if(f) connectIfNotConnected else ZIO.succeed(())
      } yield ()
    } else {
      log.debug("Tracking {} as non-intermittent", clientId)
      //TOD add re-try, catch the errors ro prevent shutting down the loop. Maybe, re-use connectiononphase here?
      connectIfNotConnected
    }

  /**
   * Start connection maintaining loop. When the returned ZIO is terminated,
   * client disconnects
   *
   * @return
   */
  def maintainConnection(finalizing: Promise[Nothing, Unit], statusCheckInterval: Duration = Duration.fromSeconds(1)): RIO[Clock, Unit] = {
    connectionMaintenanceIteration(finalizing, statusCheckInterval)
      .catchAll { err =>
        ZIO.attempt { log.error(s"Client ${clientId.value} connection check iteration failed, will try again on next iteration", err) }
      }
      .repeat(Schedule.spaced(statusCheckInterval))
      .ensuring {
//      .onExit { exitResult =>
        (for {
          _ <- ZIO.attempt { log.debug("Disconnecting client {} as maintainConnection has stopped", clientId.value) }
          _ <- disconnect()
        } yield ()).
          catchAll { err => ZIO.succeed(log.error(s"Failed to close the client ${clientId}", err)) }.
          map { _ => log.debug(s"Client ${clientId} disconnect complete") }
      }.map { _ =>
      log.debug(s"Connect ${clientId} terminated")
      ()
    }
  }


}

object MqttConnectionCommons {
  private val log = LoggerFactory.getLogger(classOf[MqttConnectionCommons])

  def disconnectAll(mqttConnections: Iterable[MqttConnectionCommons]): RIO[Clock, Unit] = ZIO.collectAllPar(mqttConnections.map(_.disconnect())).as(())
}