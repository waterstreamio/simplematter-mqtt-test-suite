package io.simplematter.mqtttestsuite.testutil

import org.eclipse.paho.client.mqttv3.{IMqttDeliveryToken, MqttCallback, MqttMessage}

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.FiniteDuration
import org.scalatest.concurrent.Eventually
import org.scalatest.OptionValues
import org.scalatest.matchers.should
import org.scalatest.time.{Millis, Seconds, Span}

import scala.language.postfixOps

class PahoCallbackProbe extends MqttCallback with Eventually with should.Matchers with OptionValues {
  private var failure: Throwable = null

  private val messagesRef: AtomicReference[Seq[TopicMessage]] = AtomicReference(Seq.empty[TopicMessage])

  implicit val defaultPatience: PatienceConfig = PatienceConfig(timeout =  Span(5, Seconds), interval = Span(20, Millis))

  override def messageArrived(topic: String, message: MqttMessage): Unit = {
    messagesRef.updateAndGet { messages => messages :+ TopicMessage(topic, message) }
  }

  override def connectionLost(cause: Throwable): Unit = {
    failure = cause
  }

  override def deliveryComplete(token: IMqttDeliveryToken): Unit = {
  }

  def getMessages(): Seq[TopicMessage] = messagesRef.get()

  def extractMessages(): Seq[TopicMessage] = messagesRef.getAndSet(Seq.empty)

  def expectMinMessages(minMessagesCount: Int, comment: String = ""): Seq[TopicMessage]  = {
    eventually {
      withClue(s"$comment actual messages=${getMessages().map(_.debugString)}") {
        getMessages().size should be >= minMessagesCount
      }
    }
    val messages = extractMessages()
    messages
  }

  def expectMessages(messagesCount: Int, comment: String = ""): Seq[TopicMessage]  = {
    eventually {
      withClue(s"$comment actual messages=${getMessages().map(_.debugString)}") {
        getMessages().size should be >= messagesCount
      }
    }
    val messages = extractMessages()
    withClue(s"$comment actual messages=${messages.map(_.debugString)}") {
      messages.size shouldBe messagesCount
    }
    messages
  }

  def expectSingleMessage(): TopicMessage = {
    expectMessages(1).headOption.value
  }
}

case class TopicMessage(topic: String, message: MqttMessage) {
  lazy val stringPayload: String = String(message.getPayload())

  lazy val debugString: String = s"$topic->$stringPayload"
}
