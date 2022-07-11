package io.simplematter.mqtttestsuite.stats

import com.hazelcast.client.HazelcastClient
import com.hazelcast.client.config.ClientConfig
import io.simplematter.mqtttestsuite.util.QuantileApprox
import io.simplematter.mqtttestsuite.config.{HazelcastConfig, MqttBrokerConfig}
import io.simplematter.mqtttestsuite.util.*
import org.slf4j.LoggerFactory
import zio.{Fiber, RIO, Schedule, Task, UIO, URIO, ZIO}
import zio.Clock
import zio.Duration
import zio.{RLayer, TaskLayer, ULayer, URLayer, ZLayer}
import zio.json.{JsonEncoder, JsonDecoder}
import zio.json._

import java.util.concurrent.{ConcurrentHashMap, ConcurrentSkipListSet, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import com.hazelcast.core.Hazelcast
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.topic.{Message, MessageListener}
import io.simplematter.mqtttestsuite.model.{ClientId, MessageId, MqttTopicName, NodeId}
import io.simplematter.mqtttestsuite.stats.StatsStorage.{messagesToDeliverMapName, mqttBrokerConfigMapName, scenarioConfigMapName, statsAcknowledgeTopicName, statsReportingTopicName}
import io.simplematter.mqtttestsuite.scenario.ScenarioState
import io.simplematter.mqtttestsuite.stats.StatsAggregator.log
import io.simplematter.mqtttestsuite.config.ScenarioConfig
import io.simplematter.mqtttestsuite.config.StatsConfig
import io.simplematter.mqtttestsuite.mqtt.MqttUtils
import io.simplematter.mqtttestsuite.stats.model.{ClientConnectEvent, ClientConnectFailed, ClientConnectSuccess, ClientConnectionClosed, ClientDisconnect, ClientEvent, ClientMessagesReceived, ClientScheduledDowntime, ClientScheduledUptime, ClientTopicSubscribed, DeliveredMessage, IssuesReport, NonDeliveredMessage, StatsSummary}

import scala.annotation.tailrec
import scala.jdk.CollectionConverters.*

class StatsStorage(nodeId: NodeId,
                   statsConfig: StatsConfig,
                   hazelcastInstance: HazelcastInstance,
                   mqttBrokerConfig: MqttBrokerConfig,
                   scenarioConfig: ScenarioConfig) extends FlightRecorder
  with StatsProvider
  with StatsProviderCommons(hazelcastInstance) {
  import StatsStorage._

  private val messagesSendAttempts: AtomicInteger = AtomicInteger(0)
  private val messagesSendInterrupts: AtomicInteger = AtomicInteger(0)
  private val messagesSendSuccess: AtomicInteger = AtomicInteger(0)
  private val messagesSendFailure: AtomicInteger = AtomicInteger(0)
  private var messageSentFirstTimestamp = AtomicLong(0)
  private var messageSentLastTimestamp = AtomicLong(0)
  private var messageSendingDurationTotal = AtomicLong(0)
  private var messagePublishRetransmitAttempts: AtomicInteger = AtomicInteger(0)
  private var messagePubrelRetransmitAttempts: AtomicInteger = AtomicInteger(0)

  private val messagesReceiveExpected: AtomicInteger = AtomicInteger(0)
  private val messagesReceived: AtomicInteger = AtomicInteger(0)
  private var messageReceivedFirstTimestamp = AtomicLong(0)
  private var messageReceivedLastTimestamp = AtomicLong(0)

  private val latencySum: AtomicLong = AtomicLong(0)
  private val latencyMax: AtomicLong = AtomicLong(0)

  private val sleepingInterval = Duration.fromSeconds(1)

  private val quantileApprox = QuantileApprox(1, 10)

  private val mqttConnectAttempts = AtomicInteger(0)
  private val mqttConnectSuccess = AtomicInteger(0)
  private val mqttConnectFailures = AtomicInteger(0)
  private val mqttConnectionsClosed = AtomicInteger(0)
  private val mqttConnectionsActive = AtomicInteger(0)
  private val mqttConnectionsExpected = AtomicInteger(0)

  private var scenarioName: String = ""
  private var scenarioState: ScenarioState = ScenarioState.New
  private var scenarioRampUpStartTimestamp: Long = 0L
  private var scenarioRampUpDurationSeconds: Int = 0
  private var scenarioStartTimestamp: Long = 0L
  private var scenarioStopTimestamp: Long = 0L
  private var scenarioDurationSeconds: Int = 0

  private val nodesBackPropagatedSnapshots = ConcurrentHashMap[NodeId, StatsStorage.BackPropagatedSnapshot]()
  private val aggregatedMessagesSent = AtomicInteger(0)
  private val aggregatedMessagesExpected = AtomicInteger(0)
  private val aggregatedMessagesReceived = AtomicInteger(0)

  private val mqttSubscribePatternsSent = AtomicInteger(0)
  private val mqttSubscribePatternsSuccess = AtomicInteger(0)
  private val mqttSubscribePatternsError = AtomicInteger(0)

  private val messageTrackingStarted = AtomicInteger(0)
  private val messageTrackingSuccess = AtomicInteger(0)
  private val messageTrackingMissing = AtomicInteger(0)
  private val messageTrackingUnexpected = AtomicInteger(0)
  private val messageTrackingError = AtomicInteger(0)

  private val snapshotIdSent = AtomicLong(0)
  private val snapshotIdAcknowledged = AtomicLong(0)

  def scenarioRampUpStarted(scenarioName: String, rampUpDurationSeconds: Int, durationSeconds: Int): URIO[Clock, Unit] =
    Clock.currentTime(TimeUnit.MILLISECONDS).map { now =>
      this.scenarioName = scenarioName
      this.scenarioState = ScenarioState.RampUp
      this.scenarioRampUpStartTimestamp = now
      this.scenarioRampUpDurationSeconds = rampUpDurationSeconds
      this.scenarioDurationSeconds = durationSeconds
    }

  def scenarioRunning(): URIO[Clock, Unit] =
    Clock.currentTime(TimeUnit.MILLISECONDS).map { now =>
      scenarioState = ScenarioState.Running
      this.scenarioStartTimestamp = now
    }

  def scenarioDone(): URIO[Clock, Unit] =
    Clock.currentTime(TimeUnit.MILLISECONDS).map { now =>
      scenarioState = ScenarioState.Done
      scenarioStopTimestamp = now
    }

  def scenarioFail(): URIO[Clock, Unit] =
    Clock.currentTime(TimeUnit.MILLISECONDS).map { now =>
      scenarioState = ScenarioState.Fail
      scenarioStopTimestamp = now
    }

  def mqttConnectionsExpected(mqttConnections: Int): UIO[Unit] = {
    ZIO.attempt {
      mqttConnectionsExpected.set(mqttConnections)
    }.ignoreLogged
  }

  def recordMqttConnect[R, A](clientId: ClientId, connect: RIO[R, A]): RIO[R with Clock, A] = {
    for {
      _ <- ZIO.attempt {
        log.debug("MQTT connect attempt for {}", clientId)
        mqttConnectAttempts.incrementAndGet()
      }
      connectOutcomeTimestamp <- Clock.currentTime(TimeUnit.MILLISECONDS)
      connectExit <- connect.exit
      res <- connectExit.mapBoth({ err =>
        log.error(s"MQTT connect failed for ${clientId}", err)
        mqttConnectFailures.incrementAndGet()
        addClientEvent(clientId, ClientConnectFailed(connectOutcomeTimestamp))
        err
      },
        { success =>
          log.debug("MQTT connect success for {}", clientId)
          mqttConnectSuccess.incrementAndGet()
          mqttConnectionsActive.incrementAndGet()
          addClientEvent(clientId, ClientConnectSuccess(connectOutcomeTimestamp))
          success
        }
      )
    } yield res
}

  def mqttConnectionClosed(clientId: ClientId, wasAccepted: Boolean): URIO[Clock, Unit] = {
    for {
      disconnectTimestamp <- Clock.currentTime(TimeUnit.MILLISECONDS)
      remainingActive = if(wasAccepted) mqttConnectionsActive.decrementAndGet() else mqttConnectionsActive.get()
      _ = mqttConnectionsClosed.incrementAndGet()
      _ = log.debug("MQTT connection closed for {}. wasAccepted={}, remainingActive={}", clientId, wasAccepted, remainingActive)
      _ = addClientEvent(clientId, ClientConnectionClosed(disconnectTimestamp))
    } yield ()
  }

  def scheduledUptime(clientId: ClientId): URIO[Clock, Unit] = {
    for {
      timestamp <- Clock.currentTime(TimeUnit.MILLISECONDS)
      _ = log.debug("MQTT scheduled uptime for {}", clientId)
      _ = addClientEvent(clientId, ClientScheduledUptime(timestamp))
    } yield ()
  }

  def scheduledDowntime(clientId: ClientId): URIO[Clock, Unit] = {
    for {
      timestamp <- Clock.currentTime(TimeUnit.MILLISECONDS)
      _ = log.debug("MQTT scheduled downtime for {}", clientId)
      _ = addClientEvent(clientId, ClientScheduledDowntime(timestamp))
    } yield ()
  }


  def recordMessageSend[R, A](id: MessageId, send: RIO[R, A], topic: MqttTopicName, recepients: Option[Iterable[ClientId]]): RIO[R with Clock, A] = {
    for {
      sendStartTimestamp <- Clock.currentTime(TimeUnit.MILLISECONDS)
      _ = messagesSendAttempts.incrementAndGet()
      _ = messageSentFirstTimestamp.updateAndGet(minNonZero(sendStartTimestamp, _))
      _ = messageSentLastTimestamp.updateAndGet(Math.max(sendStartTimestamp, _))
      _ = rememberIndividualMessageSent(id, sendStartTimestamp, topic, recepients.getOrElse(Seq.empty))
      res <- send.mapError { sendErr =>
        messagesSendFailure.incrementAndGet()
        log.info(s"Message sending failure ${id} ${sendStartTimestamp}", sendErr)
        sendErr
      }.onInterrupt(_ => ZIO.attempt {
        log.info(s"Message sending interrupt ${id} ${sendStartTimestamp}" )
        messagesSendInterrupts.incrementAndGet()
      }.ignoreLogged)
      sendEndTimestamp <- Clock.currentTime(TimeUnit.MILLISECONDS)
      _ = messageSendingDurationTotal.addAndGet(sendEndTimestamp - sendStartTimestamp)
      _ = messagesSendSuccess.incrementAndGet()
      fanOutFactor = recepients.fold(1)(_.size)
      _ = messagesReceiveExpected.addAndGet(fanOutFactor)
      _ = log.trace("Message sent successfully {} {}", id, sendStartTimestamp)
    } yield res
  }

  private def rememberIndividualMessageSent(id: MessageId, timestamp: Long, topic: MqttTopicName, recepients: Iterable[ClientId]): Unit = {
    if(statsConfig.individualMessageTracking) {
      if(scenarioConfig.nodeMessagesIsolated) {
        if(recepients.isEmpty) {
          log.debug("Message {} has no expected recepients, not remembering it", id)
        } else {
          val prev = messagesToDeliverLocal.put(id, MessageDeliveryExpectation(recepients.toSeq, timestamp, topic))
          if(prev != null) {
            log.warn("Message {} already exists", id)
            messageTrackingError.incrementAndGet()
          }
        }
      } else {
        messagesToDeliverDistributed.putAsync(id, timestamp)
        if(recepients.nonEmpty) {
          log.warn(s"Fan-out messages tracking only supported for scenarios with messages isolated to the single node. This message most likely indicates a bug in the test scenario.")
        }
      }
    }
  }

  def mqttPublishMessageRetransmitAttempt(): UIO[Unit] = {
    ZIO.succeed {
      messagePublishRetransmitAttempts.incrementAndGet()
    }
  }

  def mqttPubrelMessageRetransmitAttempt(): UIO[Unit] = {
    ZIO.succeed {
      messagePubrelRetransmitAttempts.incrementAndGet()
    }
  }

  def messageReceived(id: MessageId, topic: String, recepient: ClientId, sendingTimestamp: Long, receivingTimestamp: Long): UIO[Unit] = {
    for {
      _ <- ZIO.succeed(())
      latency = receivingTimestamp - sendingTimestamp
      _ = log.trace("Msg received: id={}, recTimestamp={}, latency={}", id, receivingTimestamp, latency)
      _ = messagesReceived.incrementAndGet()
      _ = latencySum.addAndGet(latency)
      _ = quantileApprox.add(latency)
      _ = latencyMax.updateAndGet(Math.max(_, latency))
      _ = messageReceivedFirstTimestamp.updateAndGet(minNonZero(receivingTimestamp, _))
      _ = messageReceivedLastTimestamp.updateAndGet(Math.max(receivingTimestamp, _))
      _ = rememberIndividualMessageReceived(id, topic, recepient, sendingTimestamp, receivingTimestamp)
    } yield ()
  }

  /**
   * Remove reciever from the delivery state stored in `messagesToDeliverLocal`.
   * If it's the last recepient, return None.
   *
   * @param receiver
   * @return Pair: 1. Delivery state that existed before or None 2. If a valid recepient was removed.
   */
  @tailrec
  private def removeMessageReceiverLocal(messageId: MessageId, recepient: ClientId, remainingAttempts: Int = 50): (Option[MessageDeliveryExpectation], Boolean) = {
    val existingDeliveryOpt = Option(messagesToDeliverLocal.get(messageId))
    if (remainingAttempts <= 0) {
      log.error(s"Attempts exceeded for removeMessageReceiverLocal(${messageId}, ${recepient})")
      (existingDeliveryOpt, false)
    } else {
      val (retry, removed) = existingDeliveryOpt match {
        case None => (false, false)
        case Some(existingDelivery) if existingDelivery.remainingRecepients.size <= 1 =>
          (!messagesToDeliverLocal.remove(messageId, existingDelivery), true)
        case Some(existingDelivery) =>
          val (removedRecepients, remainingRecepients) = existingDelivery.remainingRecepients.partition(_ == recepient)
          if (removedRecepients.isEmpty) {
            log.warn(s"Message ${messageId} doesn't have recepient ${recepient}")
            (false, false)
          } else {
            if (remainingRecepients.isEmpty) {
              (!messagesToDeliverLocal.remove(messageId, existingDelivery), true)
            } else {
              (!messagesToDeliverLocal.replace(messageId, existingDelivery, existingDelivery.copy(remainingRecepients = remainingRecepients)), true)
            }
          }
      }
      if (retry) {
        removeMessageReceiverLocal(messageId, recepient, remainingAttempts - 1)
      } else {
        (existingDeliveryOpt, removed)
      }
    }
  }

  private def rememberExpecteMessage(id: MessageId, topic: String, recepient: ClientId, sendingTimestamp: Long, receivingTimestamp: Long): Unit = {
    expectedMessagesDeliveredByClientTmp.compute(recepient, {(_, existingMessages) =>
      val msg = DeliveredMessage(id, topic, sendingTimestamp, recepient, receivingTimestamp)
      if(existingMessages == null)
        Seq(msg)
      else
        existingMessages :+ msg
    })
  }

  private def rememberUnexpecteMessage(id: MessageId, topic: String, recepient: ClientId, sendingTimestamp: Long, receivingTimestamp: Long): Unit = {
    unexpectedMessagesDeliveredMap.compute(id, {(key, existingOutcomes) =>
      val outcome = MessageDeliveryOutcome(recepient, topic, sendingTimestamp, receivingTimestamp)
      if(existingOutcomes == null)
        Seq(outcome)
      else
        existingOutcomes :+ outcome
    })
  }

  private def rememberIndividualMessageReceived(id: MessageId, topic: String, recepient: ClientId, sendingTimestamp: Long, receivingTimestamp: Long): Unit = {
    if(statsConfig.individualMessageTracking) {
      if(scenarioConfig.nodeMessagesIsolated) {
        val (prevDeliveryState, recepientRemoved) = removeMessageReceiverLocal(id, recepient)
        if(recepientRemoved) {
          messageTrackingSuccess.incrementAndGet()
          rememberExpecteMessage(id, topic, recepient, sendingTimestamp, receivingTimestamp)
        } else {
          messageTrackingUnexpected.incrementAndGet()
          log.warn(s"Message ${id} not intended for the recepient ${recepient}. Delivery state: ${prevDeliveryState}")
          rememberUnexpecteMessage(id, topic, recepient, sendingTimestamp, receivingTimestamp)
        }
      } else {
        //No recepient cheching supported here, we'll just remember what is provided - for the purpose of the error tracking
        messagesToDeliverDistributed.removeAsync(id).whenComplete((v, err) =>
          if (err != null) {
            messageTrackingError.incrementAndGet()
            log.warn(s"Failed to clear message ${id} delivery status", err)
          } else if (v == null) {
            //TODO this may result in false positives due to the concurrency issues
            messageTrackingUnexpected.incrementAndGet()
            log.warn(s"Message ${id} received but I don't remember sending it")
            rememberUnexpecteMessage(id, topic, recepient, sendingTimestamp, receivingTimestamp)
          } else {
            messageTrackingSuccess.incrementAndGet()
            rememberExpecteMessage(id, topic, recepient, sendingTimestamp, receivingTimestamp)
            log.trace("Msg cleaned: {} -> {}", id, v)
          })
      }
    }
  }


  override def mqttSubscribeSent(clientId: ClientId, topics: Seq[String]): URIO[Clock, Unit] = {
    ZIO.succeed {
      mqttSubscribePatternsSent.addAndGet(topics.size)
    }
  }

  override def mqttSubscribeAcknowledged(clientId: ClientId, topicsWithCodes: Seq[(String, Int)]): URIO[Clock, Unit] = {
    Clock.currentTime(TimeUnit.MILLISECONDS).map { now =>
      val errCount = topicsWithCodes.count {(_, code) => MqttUtils.isSubAckError(code) }
      mqttSubscribePatternsError.addAndGet(errCount)
      mqttSubscribePatternsSuccess.addAndGet(topicsWithCodes.size - errCount)
      addClientEvent(clientId, ClientTopicSubscribed(now, topicsWithCodes))
    }
  }

  private def getScenario(): StatsSummary.Scenario = StatsSummary.Scenario(
    scenarioName = scenarioName,
    scenarioState = scenarioState,
    nodes = 1,
    rampUpStartTimestamp = scenarioRampUpStartTimestamp,
    rampUpDurationSeconds = scenarioRampUpDurationSeconds,
    startTimestamp = scenarioStartTimestamp,
    scenarioStopTimestamp = scenarioStopTimestamp,
    durationSeconds = scenarioDurationSeconds
  )

  private def getMessagesSent(): StatsSummary.SentMessages = StatsSummary.SentMessages(
    attempts = messagesSendAttempts.get - messagesSendInterrupts.get(),
    success = messagesSendSuccess.get(),
    failure = messagesSendFailure.get(),
    publishRetransmitAttempts = messagePublishRetransmitAttempts.get(),
    pubrelRetransmitAttempts = messagePubrelRetransmitAttempts.get(),
    firstTimestamp = messageSentFirstTimestamp.get(),
    lastTimestamp = messageSentLastTimestamp.get(),
    sendingDuration = messageSendingDurationTotal.get()
  )

  private def getMessagesReceived(): StatsSummary.ReceivedMessages = StatsSummary.ReceivedMessages(
    expectedCount = messagesReceiveExpected.get(),
    count = messagesReceived.get,
    firstTimestamp = messageReceivedFirstTimestamp.get(),
    lastTimestamp = messageReceivedLastTimestamp.get(),
    trackingSuccess = messageTrackingSuccess.get(),
    trackingMissing = messageTrackingMissing.get(),
    trackingUnexpected = messageTrackingUnexpected.get(),
    trackingError = messageTrackingError.get()
  )

  def getSnapshot(): StatsStorage.Snapshot = StatsStorage.Snapshot(
    nodeId = nodeId,
    snapshotId = snapshotIdSent.incrementAndGet(),
    mqttConnectAttempts = mqttConnectAttempts.get(),
    mqttConnectFailures = mqttConnectFailures.get(),
    mqttConnectSuccess = mqttConnectSuccess.get(),
    mqttConnectClose = mqttConnectionsClosed.get(),
    mqttConnectionsActive = mqttConnectionsActive.get(),
    mqttConnectionsExpected = mqttConnectionsExpected.get(),
    mqttSubscribePatternsSent = mqttSubscribePatternsSent.get(),
    mqttSubscribePatternsSuccess = mqttSubscribePatternsSuccess.get(),
    mqttSubscribePatternsError = mqttSubscribePatternsError.get(),
    scenario = getScenario(),
    sent = getMessagesSent(),
    received = getMessagesReceived(),
    latencySum = latencySum.get(),
    latencyMax = latencyMax.get(),
    quantileBuckets = quantileApprox.snapshot().buckets
  )

  def getStats(): StatsSummary = {
    val msgRec = messagesReceived.get()
    StatsSummary(
      mqttConnectAttempts = mqttConnectAttempts.get(),
      mqttConnectFailures = mqttConnectFailures.get(),
      mqttConnectSuccess = mqttConnectSuccess.get(),
      mqttConnectClose = mqttConnectionsClosed.get(),
      mqttConnectionsActive = mqttConnectionsActive.get(),
      mqttConnectionsExpected = mqttConnectionsExpected.get(),
      mqttSubscribePatternsSent = mqttSubscribePatternsSent.get(),
      mqttSubscribePatternsSuccess = mqttSubscribePatternsSuccess.get(),
      mqttSubscribePatternsError = mqttSubscribePatternsError.get(),
      scenario = getScenario(),
      sent = getMessagesSent(),
      received = getMessagesReceived(),
      latencyAvg = if(msgRec > 0) latencySum.get() / msgRec else 0,
      latencyMax = latencyMax.get(),
      latencyP50 = quantileApprox.quantile(0.5),
      latencyP75 = quantileApprox.quantile(0.75),
      latencyP95 = quantileApprox.quantile(0.95),
      latencyP99 = quantileApprox.quantile(0.99)
    )
  }

  override def getMqttBrokerConfig(): Option[MqttBrokerConfig] = Option(mqttBrokerConfig)

  override def getScenarioConfig(): Option[ScenarioConfig] = Option(scenarioConfig)


  override def getIssuesReport(): IssuesReport = {
    Option(issuesReportMap.get(nodeId)).getOrElse(IssuesReport())
  }

  /**
   * Wait until the pending actions finish to make sure it's safe to shut down the stats storage and the Hazelcast instance
   *
   * @param timeout
   * @return
   */
  def waitCompletion(timeout: Duration): RIO[Clock, Boolean]  = {
//    ZIO.attempt {
//      log.info("***** dummy wait")
//      false
//    }
    ZIO.attempt {
      log.debug("***** waiting attempt")
      val sent = aggregatedMessagesSent.get()
      val expected = aggregatedMessagesExpected.get()
      val received = aggregatedMessagesReceived.get()
      val missingMessages = expected - received
      val sentSnapshot = snapshotIdSent.get()
      val acknowledgedSnapshot = snapshotIdAcknowledged.get()
      val canAcknowledgeSnapshots = hazelcastInstance.getCluster.getMembers.size() > 1
      log.debug("Aggregated messages not received yet: {} - {} = {}. Snapshot sent: {}, acknowledged: {}", expected, received, missingMessages, sentSnapshot, acknowledgedSnapshot)
      if(missingMessages < 0) {
        //TODO better reporting
        log.warn("More messages received than expected - sent: {}, expected receive: {}, received: {}", sent, expected, received)
      }
      if(acknowledgedSnapshot > sentSnapshot) {
        //TODO better reporting
        log.warn("More snapshots acknolwedged than sent - sent: {}, acknowledged: {}", sentSnapshot, acknowledgedSnapshot)
      }
//      missingMessages <= 0 && (acknowledgedSnapshot >= sentSnapshot || !canAcknowledgeSnapshots)
      missingMessages == 0 && (acknowledgedSnapshot >= sentSnapshot || !canAcknowledgeSnapshots)
    }.repeat(Schedule.spaced(sleepingInterval) && Schedule.recurUntilEquals(true))
      .timeoutTo(false)(remaining => true)(timeout)
      .map { complete =>
        if (complete)
          log.info(s"Finished waiting for the remaining messages.")
        else
          log.info(s"Timed out waiting ${timeout} for messages to complete. ${aggregatedMessagesSent.get() - aggregatedMessagesReceived.get()} messages remain.")
        complete
        }
  }

  private def publishStats(): Task[Unit] = {
    ZIO.attemptBlocking {
      statsReportingTopic.publish(getSnapshot().toJson)
    }
  }

  private def statsPublishingLoop(interval: Duration): RIO[Clock, Unit] = {
    publishStats()
      .repeat(Schedule.spaced(interval))
      .onInterrupt(publishStats().logExceptions("Final stats report failed", log, ()))
      .as(())
  }

  private def listenForUpdates(): Unit = {
    log.debug(s"Listening for stats updates on topic ${StatsStorage.statsReportingTopicName}")

    statsReportingTopic.addMessageListener(new MessageListener[String] {
      def onMessage(msg: Message[String]): Unit = {
        msg.getMessageObject.fromJson[StatsStorage.Snapshot].fold({ decodingError =>
          log.error("Failed to decode a snapshot {}: {}", msg.getMessageObject, decodingError)
        }, { snapshot =>
          log.trace("Got stats snapshot: {}", snapshot)
          registerSnapshot(snapshot)
        })
      }
    })

    log.debug(s"Listening for stats ack on topic ${StatsStorage.statsAcknowledgeTopicName}")

    statsAcknowledgeTopic.addMessageListener(new MessageListener[String] {
      override def onMessage(msg: Message[String]): Unit = {
        msg.getMessageObject.fromJson[StatsStorage.SnapshotAck].fold({ decodingError =>
          log.error("Failed to decode a snapshot ack {}: {}", msg.getMessageObject, decodingError)
        }, { snapshotAck =>
          log.trace("Got stats snapshot ack: {}, current node: {}", snapshotAck, nodeId)
          if(snapshotAck.nodeId == nodeId) {
            snapshotIdAcknowledged.updateAndGet(Math.max(_, snapshotAck.snapshotId))
          }
        })
      }
    })
  }

  /**
   * Remove `messagesToDeliverMap` items that have a corresponding `unexpectedMessagesDeliveredMap` item
   * and vice versa
   *
   * @return Remaining message IDs for missing messages
   */
  private def annihilateMissingAndUnexpected(): Set[MessageId] = {
    val messagesToDeliverSet = messagesToDeliverDistributed.keySet().asScala.toSet

    val (annihilatedMessages, remainingMissing) = messagesToDeliverSet.partition(unexpectedMessagesDeliveredMap.containsKey)
    annihilatedMessages.foreach(unexpectedMessagesDeliveredMap.remove)

    annihilatedMessages.foreach { msgId =>
      messagesToDeliverDistributed.removeAsync(msgId).whenComplete { (v, err) =>
        if (err != null) {
          messageTrackingError.incrementAndGet()
          log.warn(s"Failed to clear annihilated message ${msgId} from messagesToDeliverMap", err)
        } else {
          messageTrackingSuccess.incrementAndGet()
          log.trace("Cleared annihilated message {} from messagesToDeliverMap", msgId)
        }
      }
    }

    log.debug("Annihilated traces for distributed messages: {}, remaining missing: {}, remaining unexpected: {}",
      annihilatedMessages.size, remainingMissing.size, unexpectedMessagesDeliveredMap.size)

    remainingMissing
  }

  private def detectErrors(): RIO[Clock, Unit]  = {
    Clock.currentTime(TimeUnit.MILLISECONDS).map { now =>
      val timestampThreshold = Math.max(now - statsConfig.considerMessageMissingTimeoutMillis, 0)
      log.debug("Checking the stats for errors detection")

      val missingMessages = if(scenarioConfig.nodeMessagesIsolated) {
          messagesToDeliverLocal.asScala.flatMap { case (msgId: MessageId, deliveryState: MessageDeliveryExpectation) =>
          if(deliveryState.timestamp <= timestampThreshold)
            Seq(NonDeliveredMessage(msgId, deliveryState.topic.value, deliveryState.timestamp, deliveryState.remainingRecepients))
          else
            Seq.empty
        }.toSeq
      } else {
        val remainingMsgIds = annihilateMissingAndUnexpected()
        messageTrackingUnexpected.set(unexpectedMessagesDeliveredMap.size())

        //TODO consider wrapping into the blocking executor
        remainingMsgIds.flatMap { msgId =>
          val res = messagesToDeliverDistributed.get(msgId) match {
            case t: Long if t <= timestampThreshold => Seq(NonDeliveredMessage(msgId, "TODO", t, Seq.empty))
            case _ => Seq.empty
          }
          res
        }.toSeq
      }

      messageTrackingMissing.set(missingMessages.size)

      val unexpectedMessages = unexpectedMessagesDeliveredMap.asScala.flatMap {
          (msgId, outcomes) => outcomes.map(outcome => DeliveredMessage(msgId, outcome.topic, outcome.sendingTimestamp, outcome.recepient, outcome.receivingTimestamp))
      }.toSeq

      aggregateExpectedMessagesStats()

      val issuesReport = IssuesReport(messagesNotDelivered = missingMessages.take(statsConfig.maxErrors),
        messagesNotDeliveredCount = missingMessages.size,
        unexpectedMessagedDelivered = unexpectedMessages.take(statsConfig.maxErrors),
        unexpectedMessagedDeliveredCount = unexpectedMessages.size,
        clientEvents = clientEvents.asScala.toMap
      )

      issuesReportMap.put(nodeId, issuesReport)
      val endTs = System.currentTimeMillis()
      log.debug("Issues report for {} set successfully in {} ms", nodeId, endTs - now)
    }
  }

  /**
   * Compact the individual messages from  expectedMessagesDeliveredByClientTmp into
   * the aggregated statistics in clientEvents
   *
   * @param maxSampleSize
   */
  private def aggregateExpectedMessagesStats(maxSampleSize: Int = 10): Unit = {
    expectedMessagesDeliveredByClientTmp.forEachKey(1, { clientId =>
      val messages = expectedMessagesDeliveredByClientTmp.remove(clientId)
      if(messages != null && messages.nonEmpty) {
        addClientEvent(clientId, ClientMessagesReceived.build(messages, maxSampleSize))
      }
    })
  }

  private def errorsDetectionLoop(): RIO[Clock, Unit] = {
    if(statsConfig.individualMessageTracking) {
      detectErrors()
        .repeat(Schedule.spaced(Duration.fromMillis(statsConfig.errorsDetectionLoopIntervalMillis)))
        .ensuring((for {
            _ <- ZIO.attempt { log.debug("Final errors detection started")}
            _ <- detectErrors().logExceptions("Final errors detection failed", log, ())
          } yield ()).ignoreLogged).unit
    } else {
      log.debug("Individual message tracking disabled, no need for the errors detection loop")
      ZIO.succeed(())
    }
  }

  def registerSnapshot(snapshot: StatsStorage.Snapshot): Unit = {
    val prevSnapshot = Option(nodesBackPropagatedSnapshots.put(snapshot.nodeId, snapshot.backPropagated()))
    val prevSent = prevSnapshot.fold(0)(_.sentMessagesCount)
    aggregatedMessagesSent.addAndGet(snapshot.sent.success - prevSent)
    val prevExpected= prevSnapshot.fold(0)(_.expectedReceiveMessagesCount)
    aggregatedMessagesExpected.addAndGet(snapshot.received.expectedCount - prevExpected)
    val prevReceived = prevSnapshot.fold(0)(_.receivedMessagesCount)
    aggregatedMessagesReceived.addAndGet(snapshot.received.count - prevReceived)
  }

  private def addClientEvent(clientId: ClientId, event: ClientEvent): Unit = {
    clientEvents.compute(clientId, { (_, events) =>
      if(events == null)
        Seq(event)
      else
        events :+ event
    })
  }


  private lazy val statsReportingTopic = hazelcastInstance.getTopic[String](statsReportingTopicName)
  private lazy val statsAcknowledgeTopic = hazelcastInstance.getTopic[String](statsAcknowledgeTopicName)

  private lazy val messagesToDeliverLocal = ConcurrentHashMap[MessageId, MessageDeliveryExpectation]()
  private lazy val messagesToDeliverDistributed = hazelcastInstance.getMap[MessageId, Long | Null](messagesToDeliverMapName)

  private val clientEvents = ConcurrentHashMap[ClientId, Seq[ClientEvent]]
  private lazy val expectedMessagesDeliveredByClientTmp = ConcurrentHashMap[ClientId, Seq[DeliveredMessage]]
//  private lazy val expectedMessagesDeliveredByClientAgg = ConcurrentHashMap[ClientId, Seq[ClientMessagesReceived]]
  private lazy val unexpectedMessagesDeliveredMap = ConcurrentHashMap[MessageId, Seq[MessageDeliveryOutcome]]()
}

object StatsStorage {
  private val log = LoggerFactory.getLogger(classOf[StatsStorage])

  case class Snapshot(nodeId: NodeId,
                      snapshotId: Long,
                      mqttConnectAttempts: Int,
                      mqttConnectFailures: Int,
                      mqttConnectSuccess: Int,
                      mqttConnectClose: Int,
                      mqttConnectionsActive: Int,
                      mqttConnectionsExpected: Int,
                      mqttSubscribePatternsSent: Int,
                      mqttSubscribePatternsSuccess: Int,
                      mqttSubscribePatternsError: Int,
                      sent: StatsSummary.SentMessages,
                      received: StatsSummary.ReceivedMessages,
                      scenario: StatsSummary.Scenario,
                      latencySum: Long,
                      latencyMax: Long,
                      quantileBuckets: Seq[Int]) {
    def backPropagated(): BackPropagatedSnapshot =
      BackPropagatedSnapshot(sent.success, received.expectedCount, received.count)
  }

  object Snapshot {
    import io.simplematter.mqtttestsuite.model.JsonCodecs._

    implicit val encoder: JsonEncoder[Snapshot] = DeriveJsonEncoder.gen[Snapshot]
    implicit val decoder: JsonDecoder[Snapshot] = DeriveJsonDecoder.gen[Snapshot]
  }

  /**
   * Snapshot pieces relevant for the back-propagated data
   */
  case class BackPropagatedSnapshot(sentMessagesCount: Int, expectedReceiveMessagesCount: Int, receivedMessagesCount: Int)

   /**
   * Acknowledgement from the stats aggregator
    *
   * @param nodeId
   * @param snapshotId
   */
  case class SnapshotAck(nodeId: NodeId, snapshotId: Long)

  object SnapshotAck {
    import io.simplematter.mqtttestsuite.model.JsonCodecs._

    implicit val encoder: JsonEncoder[SnapshotAck] = DeriveJsonEncoder.gen[SnapshotAck]
    implicit val decoder: JsonDecoder[SnapshotAck] = DeriveJsonDecoder.gen[SnapshotAck]
  }

  case class MessageDeliveryExpectation(remainingRecepients: Seq[ClientId], timestamp: Long, topic: MqttTopicName)

  case class MessageDeliveryOutcome(recepient: ClientId, topic: String, sendingTimestamp: Long, receivingTimestamp: Long)

  def layer(nodeId: NodeId,
            statsConfig: StatsConfig,
            statsReportingInterval: Duration,
            mqttBrokerConfig: MqttBrokerConfig,
            scenarioConfig: ScenarioConfig): RLayer[HazelcastInstance with Clock, StatsStorage with FlightRecorder] = {
    ZLayer.scoped {
      ZIO.acquireRelease({
        for { hz <- ZIO.service[HazelcastInstance]
             _ = log.debug("statsConfig: {}", statsConfig)
             storage = StatsStorage(nodeId, statsConfig, hz, mqttBrokerConfig, scenarioConfig)
             mqttBrokerConfigMap = hz.getMap[NodeId, String](mqttBrokerConfigMapName)
             scenarioConfigMap = hz.getMap[NodeId, String](scenarioConfigMapName)
             _ = mqttBrokerConfigMap.put(nodeId, mqttBrokerConfig.toJson)
             _ = scenarioConfigMap.put(nodeId, scenarioConfig.toJson)
             publishingFiber <- storage.statsPublishingLoop(statsReportingInterval).interruptible.fork
             errorDetectionFiber <- storage.errorsDetectionLoop().interruptible.fork
             _ = storage.listenForUpdates()
//             } yield (Has.allOf[StatsStorage, FlightRecorder](storage, storage), publishingFiber.zip(errorDetectionFiber))
        } yield (zio.ZEnvironment(storage, storage), publishingFiber.zip(errorDetectionFiber))
    })({
        res =>
          //TODO terminate all StatsStorage activities here, release all the fibers
          for {
            _ <- ZIO.succeed(())
            _ = log.debug("Shutting down StatsStorage background activities")
            _ <- res._2.interrupt
          } yield ()
      }).map(_._1.get)
    }
  }

//    ZLayer.fromAcquireReleaseMany[HazelcastInstance with Clock, Throwable, (StatsStorage with FlightRecorder, Fiber[Throwable, Any])] {
//      for {hz <- ZIO.service[HazelcastInstance]
//           _ = log.debug("statsConfig: {}", statsConfig)
//           storage = StatsStorage(nodeId, statsConfig, hz, mqttBrokerConfig, scenarioConfig)
//           mqttBrokerConfigMap = hz.getMap[NodeId, String](mqttBrokerConfigMapName)
//           scenarioConfigMap = hz.getMap[NodeId, String](scenarioConfigMapName)
//           _ = mqttBrokerConfigMap.put(nodeId, mqttBrokerConfig.toJson)
//           _ = scenarioConfigMap.put(nodeId, scenarioConfig.toJson)
//           publishingFiber <- storage.statsPublishingLoop(statsReportingInterval).interruptible.fork
//           errorDetectionFiber <- storage.errorsDetectionLoop().interruptible.fork
//           _ = storage.listenForUpdates()
//           } yield (Has.allOf[StatsStorage, FlightRecorder](storage, storage), publishingFiber.zip(errorDetectionFiber))
//  } { res =>
//      //TODO terminate all StatsStorage activities here, release all the fibers
//      for {
//        _ <- ZIO.succeed(())
//        _ = log.debug("Shutting down StatsStorage background activities")
//        _ <- res._2.interrupt
//      } yield ()
//    }.map (_._1)
//  }

  def waitCompletion(timeout: Duration): RIO[Clock with StatsStorage, Boolean] =
      ZIO.service[StatsStorage].flatMap(_.waitCompletion(timeout))

  /**
   * Prepare for printing the final stats and shutting down
   *
   * @return
   */
  def finalizeStats(): RIO[Clock with StatsStorage, Unit] =
    ZIO.service[StatsStorage].flatMap { statsStorage =>
      for {
        _ <- statsStorage.detectErrors()
        _ <- statsStorage.publishStats()
      } yield ()
    }

  val statsReportingTopicName = "mqtt_test_stats_reporting"
  val statsAcknowledgeTopicName = "mqtt_test_stats_acknowledge"
  val mqttBrokerConfigMapName = "mqtt_test_mqtt_broker_config"
  val scenarioConfigMapName = "mqtt_test_scenario_config"
  val messagesToDeliverMapName = "mqtt_messages_to_deliver"
}
