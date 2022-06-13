package io.simplematter.mqtttestsuite.stats.model

import io.simplematter.mqtttestsuite.model.{ClientId, MessageId}
import io.simplematter.mqtttestsuite.stats.presentation.{ColumnHead, CompositeText, LinkToClientIssues, PlainText, ReportOutputModel, Table, Text, LinkText}
import io.simplematter.mqtttestsuite.util.{concatBeginningUpTo, concatEndUpTo, minNonZero}

import java.time.{Instant, LocalDateTime, LocalTime}

case class IssuesReport(
                        messagesNotDelivered: Seq[NonDeliveredMessage] = Seq.empty,
                        messagesNotDeliveredCount: Int = 0,
                        unexpectedMessagedDelivered: Seq[DeliveredMessage] = Seq.empty,
                        unexpectedMessagedDeliveredCount: Int = 0,
                        clientEvents: Map[ClientId, Seq[ClientEvent]] = Map.empty,
                        reportTimestamp: Long = 0) {

  private def clientsListText(title: String, clientIds: Seq[ClientId], maxItems: Int = 1000): Text = {
    if(clientIds.isEmpty)
      PlainText(title + "None")
    else
      CompositeText(Seq(
        PlainText(title),
        PlainText(clientIds.size.toString),
        PlainText.space,
        PlainText.openParentheses, LinkText.toClientIssues(clientIds.head)) ++
        clientIds.tail.take(maxItems - 1).flatMap(clientId => Seq(PlainText.commaSpace, LinkText.toClientIssues(clientId))) ++
        Seq(if(clientIds.size <= maxItems) PlainText.closeParentheses else PlainText(s", ... ${clientIds.size - maxItems} more)"))
      )
  }

  def buildOutput(now: Long, label: Option[String] = None): ReportOutputModel = {
    val headContent = "TEST ISSUES" + label.fold("")(" (" + _ + ")")

    val problematicClients = clientsWithIssues()
    val okClients = (clientEvents.keySet -- problematicClients.toSet).toSeq.sortBy(_.value)

    ReportOutputModel(title = headContent,
      items = Seq(
        clientsListText("Clients with issues: ", problematicClients),
        PlainText(s"Non-delivered messages: ${truncatedItemsWithCount(messagesNotDeliveredCount, messagesNotDelivered.map(_.toString))}"),
        PlainText(s"Unexpected messages: ${truncatedItemsWithCount(unexpectedMessagedDeliveredCount, unexpectedMessagedDelivered.map(_.toString))}"),
        clientsListText("Clients without issues: ", okClients)
      )
    )
  }

  private def formatCol(content: String, width: Int): String = {
    content + " ".repeat(Math.max(0, width - content.length))
  }

  def buildOutputByClient(clientId: ClientId, now: Long, label: Option[String] = None): ReportOutputModel = {
    val headWidth = 100
    val headContent = "TEST ISSUES FOR CLIENT " + clientId + " " + label.fold("")(" (" + _ + ")")

    val clientEvents = clientEventsExtended(clientId)

    val undeliveredMsgSentCount = clientEvents.count(_.isInstanceOf[ClientUndeliveredMessageSent])
    val missedDeliveriesCount = clientEvents.count(_.isInstanceOf[ClientMessageNotReceived])
    val unexpectedMsgSentCount = clientEvents.count(_.isInstanceOf[ClientUnexpectedMessageSent])
    val unexpectedMsgReceivedCount = clientEvents.count(_.isInstanceOf[ClientUnexpectedMessageReceived])

    ReportOutputModel(title = headContent,
      items = Seq(
        PlainText(s"Undelivered msg sent: ${undeliveredMsgSentCount}"),
        PlainText(s"Deliveries missed: ${missedDeliveriesCount}"),
        PlainText(s"Unexpected msg sent: ${unexpectedMsgSentCount}"),
        PlainText(s"Unexpected msg received: ${unexpectedMsgReceivedCount}"),
        PlainText("Events:"),
        Table(Seq(ColumnHead("Timestamp", Option(28)), ColumnHead("Event", Option(32)), ColumnHead("Params"), ColumnHead("Related clients", Option(50))),
          clientEvents.map(event => event.tabular().map(PlainText.apply) :+ clientsListText("", event.relatedClients))
        )
      )
    )
  }

  def clientsWithIssues(): Seq[ClientId] = {
    (messagesNotDelivered.flatMap(msg => msg.id.clientId +: msg.remainingRecepients) ++
      unexpectedMessagedDelivered.flatMap(msg => Seq(msg.id.clientId, msg.recepient))).toSet.toSeq.sortBy(_.value)
  }

  def clientEventsExtended(clientId: ClientId): Seq[ClientEvent] = {
    (messagesNotDelivered.filter(_.id.clientId == clientId).map(msg => ClientUndeliveredMessageSent(msg)) ++
      messagesNotDelivered.filter(_.remainingRecepients.contains(clientId)).map(ClientMessageNotReceived.apply) ++
      unexpectedMessagedDelivered.collect {
        case msg if msg.id.clientId == clientId => ClientUnexpectedMessageSent(msg)
        case msg if msg.recepient == clientId => ClientUnexpectedMessageReceived(msg)
    } ++ clientEvents.getOrElse(clientId, Seq.empty)
      ).sortBy(_.timestamp)
  }

  def aggregate(other: IssuesReport): IssuesReport = {
    //TODO performance improvement: sort the messages in the aggregate, truncate after merge
    IssuesReport(this.messagesNotDelivered ++ other.messagesNotDelivered,
      this.messagesNotDeliveredCount + other.messagesNotDeliveredCount,
      this.unexpectedMessagedDelivered ++ other.unexpectedMessagedDelivered,
      this.unexpectedMessagedDeliveredCount + other.unexpectedMessagedDeliveredCount,
      this.clientEvents ++ other.clientEvents, /* TODO this assumes that the clientIds don't intersect. Need an explicit check for it. */
      Seq(this.reportTimestamp, other.reportTimestamp).filter(_ > 0).minOption.getOrElse(0)
    )
  }

  private def truncatedItemsWithCount(totalItemsCount: Int, displayingItems: Iterable[String], maxItems: Int = 1000): String = {
    if (displayingItems.isEmpty && totalItemsCount == 0) {
      "None"
    } else {
      val sortedItems = displayingItems.toSeq.sorted
      if (sortedItems.size < maxItems) {
        totalItemsCount + " (" + sortedItems.mkString(", ") + ")"
      } else {
        val displayingItemsHead = sortedItems.take(maxItems)
        totalItemsCount + " (" + displayingItemsHead.mkString(", ") + s", ... ${totalItemsCount - displayingItemsHead.size} more)"
      }
    }
  }
}

object IssuesReport {
//  val empty = IssuesReport(Set.empty, Set.empty)
}

case class NonDeliveredMessage(id: MessageId, topic: String, sendingTimestamp: Long, remainingRecepients: Seq[ClientId]) extends Ordered[NonDeliveredMessage] {
  override def compare(that: NonDeliveredMessage): Int =
    sendingTimestamp.compare(that.sendingTimestamp)
}

case class DeliveredMessage(id: MessageId, topic: String, sendingTimestamp: Long, recepient: ClientId, receivingTimestamp: Long) extends Ordered[DeliveredMessage] {
  import scala.math.Ordered.orderingToOrdered

  override def compare(that: DeliveredMessage): Int =
    (receivingTimestamp, sendingTimestamp).compare(that.receivingTimestamp, that.sendingTimestamp)
}


sealed trait ClientEvent {
  val timestamp: Long

  def tabular(): Seq[String] = Seq(
    Instant.ofEpochMilli(timestamp).toString,
    this.getClass.getSimpleName,
    paramsStr
  )

  def paramsStr: String = ""

  def relatedClients: Seq[ClientId] = Seq.empty
}

trait ClientConnectEvent extends ClientEvent

trait ClientMessageEvent extends ClientEvent

trait ClientMessageErrorEvent extends ClientMessageEvent

case class ClientConnectSuccess(override val timestamp: Long) extends ClientConnectEvent

case class ClientConnectFailed(override val timestamp: Long) extends ClientConnectEvent

case class ClientDisconnect(override val timestamp: Long) extends ClientConnectEvent

case class ClientConnectionClosed(override val timestamp: Long) extends ClientConnectEvent

case class ClientScheduledUptime(override val timestamp: Long) extends ClientConnectEvent

case class ClientScheduledDowntime(override val timestamp: Long) extends ClientConnectEvent

case class ClientTopicSubscribed(override val timestamp: Long, topicsWithCodes: Seq[(String, Int)]) extends ClientMessageEvent {
  override def paramsStr: String = s"topicsWithCodes=${topicsWithCodes.mkString(", ")}"
}

case class ClientUndeliveredMessageSent(message: NonDeliveredMessage) extends ClientMessageErrorEvent {
  override val timestamp = message.sendingTimestamp

  override def paramsStr: String = s"msgId=${message.id}"

  override def relatedClients: Seq[ClientId] = message.remainingRecepients
}

case class ClientMessagesSent(count: Int, firstSendingTimestamp: Long, lastSendingTimestamp: Long) extends ClientMessageEvent {
  override val timestamp: Long = firstSendingTimestamp

  override def paramsStr: String = s"count=$count"
}

case class ClientUnexpectedMessageSent(message: DeliveredMessage) extends ClientMessageErrorEvent {
  override val timestamp: Long = message.sendingTimestamp

  override def paramsStr: String = s"msgId=${message.id}, topic=${message.topic}, sentAt=${Instant.ofEpochMilli(message.sendingTimestamp).toString()}, recepient=${message.recepient}, receivedAt=${Instant.ofEpochMilli(message.receivingTimestamp).toString()}"

  override def relatedClients: Seq[ClientId] = Seq(message.recepient)
}

case class ClientMessageNotReceived(message: NonDeliveredMessage) extends ClientMessageErrorEvent {
  override val timestamp: Long = message.sendingTimestamp

  override def paramsStr: String = s"msgId=${message.id}, topic=${message.topic}, sentAt=${Instant.ofEpochMilli(message.sendingTimestamp).toString()}, remainingRecepients=${message.remainingRecepients}"

  override def relatedClients: Seq[ClientId] = message.id.clientId +: message.remainingRecepients
}

case class ClientUnexpectedMessageReceived(message: DeliveredMessage) extends ClientMessageErrorEvent {
  override val timestamp: Long = message.receivingTimestamp

  override def paramsStr: String = s"msgId=${message.id}, topic=${message.topic}, sentAt=${Instant.ofEpochMilli(message.sendingTimestamp).toString()}, recepient=${message.recepient}, receivedAt=${Instant.ofEpochMilli(message.receivingTimestamp).toString()}"

  override def relatedClients: Seq[ClientId] = Seq(message.id.clientId, message.recepient)
}

case class ClientMessagesReceived(count: Int, firstReceivingTimestamp: Long, lastReceivingTimestamp: Long, head: Seq[MessageId], tail: Seq[MessageId]) extends ClientMessageEvent {
  override val timestamp: Long = firstReceivingTimestamp

  private lazy val msgIdsStr = if(tail.isEmpty)
    "[" + head.mkString(", ") + "]"
  else
    "[" + head.mkString(", ") + s", (${count - head.size - tail.size} more) , " + tail.mkString(", ") + "]"

  override def paramsStr: String = s"count=$count, start=${Instant.ofEpochMilli(firstReceivingTimestamp)}, stop=${Instant.ofEpochMilli(lastReceivingTimestamp)}, messageIds=$msgIdsStr"

  def merge(messages: Seq[DeliveredMessage], sampleMaxSize: Int = 10): ClientMessagesReceived = {
    val firstTs = minNonZero(firstReceivingTimestamp, messages.minByOption(_.receivingTimestamp).fold(0L)(_.receivingTimestamp))
    val lastTs = Math.max(lastReceivingTimestamp, messages.maxByOption(_.receivingTimestamp).fold(0L)(_.receivingTimestamp))
    val messageIds = messages.map(_.id)
    val h = if(firstTs < firstReceivingTimestamp)
      concatBeginningUpTo(messageIds, head, sampleMaxSize)
    else
      concatBeginningUpTo(head, messageIds, sampleMaxSize)
    val t = if(lastTs > lastReceivingTimestamp)
      concatEndUpTo(tail, messageIds, sampleMaxSize)
    else
      concatEndUpTo(messageIds, tail, sampleMaxSize)
    ClientMessagesReceived(count = count + messages.size,
      firstReceivingTimestamp = firstTs,
      lastReceivingTimestamp = lastTs,
      head = h,
      tail = t
    )

  }
}

object ClientMessagesReceived {

  def build(messages: Seq[DeliveredMessage], sampleMaxSize: Int = 10): ClientMessagesReceived = {
    ClientMessagesReceived(count = messages.size,
      firstReceivingTimestamp = messages.minByOption(_.receivingTimestamp).fold(0L)(_.receivingTimestamp),
      lastReceivingTimestamp = messages.maxByOption(_.receivingTimestamp).fold(0L)(_.receivingTimestamp),
      head = messages.take(sampleMaxSize).map(_.id),
      tail = messages.takeRight(Math.min(sampleMaxSize, messages.size - sampleMaxSize)).map(_.id)
    )
  }
}

