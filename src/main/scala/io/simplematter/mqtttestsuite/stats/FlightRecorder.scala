package io.simplematter.mqtttestsuite.stats

import org.slf4j.LoggerFactory
import zio.clock.Clock
import zio.{RIO, UIO, URIO, ZIO}
import zio.clock
import zio.blocking.Blocking

import java.util.concurrent.TimeUnit
import io.simplematter.mqtttestsuite.model.{ClientId, MessageId, MqttTopicName}

trait FlightRecorder {
  def scenarioRampUpStarted(scenarioName: String, rampUpDurationSeconds: Int, durationSeconds: Int): URIO[Clock, Unit]

  def scenarioRunning(): URIO[Clock, Unit]

  def scenarioDone(): URIO[Clock, Unit]

  def scenarioFail(): URIO[Clock, Unit]

  /**
   * Indicate the expected number of client connections
   *
   * @param mqttConnections
   * @return
   */
  def mqttConnectionsExpected(mqttConnections: Int): UIO[Unit]

  /**
   * Wrapper around the connect effect to track the connect latency and if it was successful
   *
   * @param connect
   * @tparam R
   * @tparam A
   * @return
   */
  def recordMqttConnect[R, A](clientId: ClientId, connect: RIO[R, A]): RIO[R with Clock, A]

  def mqttConnectionClosed(clientId: ClientId, wasAccepted: Boolean): URIO[Clock, Unit]

  def scheduledUptime(clientId: ClientId): URIO[Clock, Unit]

  def scheduledDowntime(clientId: ClientId): URIO[Clock, Unit]

  /**
   * Wrapper around the message sending effect to track if it was successful and its confirmation latency
   *
   * @param id
   * @param send
   * @param topic
   * @param recepients expected recepients (if can be tracked)
   * @return
   */
  def recordMessageSend[R, A](id: MessageId, send : RIO[R, A], topic: MqttTopicName, recepients: Option[Iterable[ClientId]]): RIO[R with Clock with Blocking, A]

  /**
   *
   * @param id
   * @param send
   * @param topic
   * @param expectedRecepients expected number of the recepients for the message
   * @tparam R
   * @tparam A
   * @return
   */
//  def recordMessageSendWithRecepients[R, A](id: MessageId, send: RIO[R, A], topic: MqttTopicName, recepients: Iterable[ClientId]): RIO[R with Clock with Blocking, A]

  def mqttPublishMessageRetransmitAttempt(): UIO[Unit]

  def mqttPubrelMessageRetransmitAttempt(): UIO[Unit]

  def messageReceived(id: MessageId, topic: String, receiver: ClientId, sendingTimestamp: Long, receivingTimestamp: Long): UIO[Unit]

//  def mqttSubscribeSent(subscriptionsCount: Int): UIO[Unit]

  def mqttSubscribeSent(clientId: ClientId, topics: Seq[String]): URIO[Clock, Unit]

//  def mqttSubscribeAcknowledged(subackCodes: Seq[Int]): UIO[Unit]

  def mqttSubscribeAcknowledged(clientId: ClientId, topicsWithCodes: Seq[(String, Int)]): URIO[Clock, Unit]

}

object FlightRecorder {
  val live = ZIO.service[FlightRecorder]

  private val log = LoggerFactory.getLogger(classOf[FlightRecorder])
}
