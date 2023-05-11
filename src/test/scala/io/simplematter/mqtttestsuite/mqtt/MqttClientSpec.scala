package io.simplematter.mqtttestsuite.mqtt

import io.netty.handler.codec.mqtt.{MqttConnectReturnCode, MqttPubReplyMessageVariableHeader, MqttPublishMessage, MqttQoS, MqttSubscriptionOption, MqttTopicSubscription}

import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.Await
import scala.concurrent.duration.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.OptionValues
import org.scalatest.time.Span
import org.scalatest.time.Seconds
import org.scalatest.time.Millis
import org.scalatest.concurrent.ScalaFutures
import org.testcontainers.containers.{BindMode, FixedHostPortGenericContainer, GenericContainer}
import org.testcontainers.utility.DockerImageName

import java.io.File
import java.nio.file.Files
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.util.ReferenceCountUtil
import io.simplematter.mqtttestsuite.model.ClientId
import io.simplematter.mqtttestsuite.testutil.{ContainerUtils, PahoCallbackProbe, TestUtils}
import org.slf4j.LoggerFactory

import java.nio.charset.StandardCharsets
import scala.concurrent.ExecutionContext.Implicits.global
import java.util.Properties
import java.util.concurrent.atomic.AtomicReference
import org.scalatest.concurrent.Eventually

class MqttClientSpec extends AnyFlatSpec
    with should.Matchers
    with ScalaFutures
    with OptionValues
    with BeforeAndAfterAll
    with Eventually {
  private val log = LoggerFactory.getLogger(classOf[MqttClientSpec])

  implicit val defaultPatience: PatienceConfig = PatienceConfig(timeout =  Span(5, Seconds), interval = Span(5, Millis))

  private val defaultTimeout = 5.seconds

  //TODO use futureValue() when https://github.com/scalatest/scalatest/issues/1961 gets fixed

  private val enableTestcontainers = true
//  private val enableTestcontainers = false
  private lazy val mqttPort: Int = if(enableTestcontainers) mosquittoContainer.getMappedPort(1883) else 1883


  private lazy val mosquittoContainer: GenericContainer[_] = ContainerUtils.mosquittoContainer()
//  private val mosquittoVersion = "2.0.11"
//  private lazy val mosquittoContainer: GenericContainer[_] = {
//    val mosquittoConfigFile = File.createTempFile("mosquitto", ".conf")
//    Files.writeString(mosquittoConfigFile.toPath,
//      s"""
//        |allow_anonymous true
//        |listener 1883 0.0.0.0
//        |log_dest stdout
//        |log_timestamp_format %Y-%m-%dT%H:%M:%S
//        |""".stripMargin)
//    mosquittoConfigFile.deleteOnExit()
//    val c = new GenericContainer(s"eclipse-mosquitto:${mosquittoVersion}")
//    c.withExposedPorts(1883)
//    c.withFileSystemBind(mosquittoConfigFile.getAbsolutePath, "/mosquitto/config/mosquitto.conf", BindMode.READ_ONLY)
//    c.waitingFor(org.testcontainers.containers.wait.strategy.Wait.forListeningPort())
//    c
//  }

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    log.debug("Starting MQTT broker")
    if(enableTestcontainers)
      mosquittoContainer.start()
  }

  override protected def afterAll(): Unit = {
    log.debug("Stopping MQTT broker")
    if(enableTestcontainers)
      mosquittoContainer.stop()
    super.afterAll()
  }

  private def connect(clientId: String, cleanSession: Boolean = true): MqttClient = {
    val options = MqttOptions(clientId = Some(ClientId(clientId)), keepAliveTimeSeconds = 5)
    val c = new MqttClient("localhost", mqttPort, options)
    Await.result(c.connect(cleanSession), defaultTimeout)
    c.isConnected() shouldBe true
    c
  }

  private def connectPaho(clientId: String = "pClient1"): (org.eclipse.paho.client.mqttv3.MqttClient, PahoCallbackProbe) = {
    val client = new org.eclipse.paho.client.mqttv3.MqttClient(s"tcp://localhost:$mqttPort", clientId, org.eclipse.paho.client.mqttv3.persist.MemoryPersistence());
    val options = new org.eclipse.paho.client.mqttv3.MqttConnectOptions()
    options.setCleanSession(true)
    val probe = new PahoCallbackProbe()
    client.setCallback(probe)
    client.connect(options)

    (client, probe)
  }

  "MqttClient" should "connect" in {
    val options = MqttOptions(clientId = Some(ClientId("test1")), keepAliveTimeSeconds = 5)
    val c = new MqttClient("localhost", mqttPort, options)

    val connack = Await.result(c.connect(), defaultTimeout)
    connack.variableHeader().connectReturnCode() shouldBe MqttConnectReturnCode.CONNECTION_ACCEPTED
    c.isConnected() shouldBe true

    Await.result(c.disconnect(), defaultTimeout)
    c.isConnected() shouldBe false

    val connack2 = Await.result(c.connect(), defaultTimeout)
    connack2.variableHeader().connectReturnCode() shouldBe MqttConnectReturnCode.CONNECTION_ACCEPTED
    c.isConnected() shouldBe true

    Await.result(c.disconnect(), defaultTimeout)
    c.isConnected() shouldBe false
  }

  it should "publish QoS0" in {
    val topic1 = "topicB1"
    val message1 = "message1"

    val c = connect("testB1")
    val (tc, tp) = connectPaho()

    tc.subscribe("#")
    val (msg1Id, pubResult1) = c.publish(topic1, Unpooled.copiedBuffer(message1, StandardCharsets.UTF_8), MqttQoS.AT_MOST_ONCE)
    msg1Id shouldBe 0 //as it's QoS0

    Await.result(pubResult1, defaultTimeout)

    val msg = tp.expectSingleMessage()
    msg.topic shouldBe topic1
    msg.stringPayload shouldBe message1
    msg.message.getQos shouldBe 0


    tc.disconnect()
    c.disconnect()
  }

  it should "publish QoS1" in {
    val topic1 = "topicC1"
    val message1 = "message1"

    val c = connect("testC1")
    val (tc, tp) = connectPaho()

    tc.subscribe("#", 1)
    var pubAckHeader: Option[MqttPubReplyMessageVariableHeader] = None
    c.onPubAckRecieve { h =>
      pubAckHeader shouldBe None
      pubAckHeader = Option(h)
    }
    val (msg1Id, pubResult1) = c.publish(topic1, Unpooled.copiedBuffer(message1, StandardCharsets.UTF_8), MqttQoS.AT_LEAST_ONCE)
    msg1Id should be > 0 //as it's QoS1

    Await.result(pubResult1, defaultTimeout)
    //By the time pubResult1 resolves, PUBACK must already be processed
    pubAckHeader.value
    pubAckHeader.foreach { header =>
      header.messageId() shouldBe msg1Id
      header.reasonCode() shouldBe MqttPubReplyMessageVariableHeader.REASON_CODE_OK
    }

    val msg = tp.expectSingleMessage()
    msg.topic shouldBe topic1
    msg.stringPayload shouldBe message1
    msg.message.getQos shouldBe 1

    tc.disconnect()
    c.disconnect()
  }

  it should "re-publish QoS1 if client re-connects" in {
    val topic1 = "topicD1"
    val message1 = "message1"

    val c = connect("testD1", cleanSession = false)
    val (tc, tp) = connectPaho()

    tc.subscribe("#", 1)
    var pubAckHeader: Option[MqttPubReplyMessageVariableHeader] = None
    c.onPubAckRecieve { h =>
      pubAckHeader shouldBe None
      pubAckHeader = Option(h)
    }
    val (msg1Id, pubResult1) = c.publish(topic1, Unpooled.copiedBuffer(message1, StandardCharsets.UTF_8), MqttQoS.AT_LEAST_ONCE)
    Await.result(c.disconnect(), defaultTimeout)

    msg1Id should be > 0 //as it's QoS1
    c.isConnected() shouldBe false

    Await.result(c.connect(false), defaultTimeout)

    //After the reconnect, client should eventually re-publish the message
    Await.result(pubResult1, defaultTimeout)
    //By the time pubResult1 resolves, PUBACK must already be processed
    pubAckHeader.value
    pubAckHeader.foreach { header =>
      header.messageId() shouldBe msg1Id
      header.reasonCode() shouldBe MqttPubReplyMessageVariableHeader.REASON_CODE_OK
    }

    val receivedMessages = tp.expectMinMessages(1, "Message with possible retransmission")
    val msg = receivedMessages.head
    msg.topic shouldBe topic1
    msg.stringPayload shouldBe message1
    msg.message.getQos shouldBe 1
    receivedMessages.last.topic shouldBe topic1
    receivedMessages.last.stringPayload shouldBe message1
    receivedMessages.last.message.getQos shouldBe 1

    tc.disconnect()
    c.disconnect()
  }

  it should "publish QoS2" in {
    val topic1 = "topicE1"
    val message1 = "message1"

    val c = connect("testE1")
    val (tc, tp) = connectPaho()

    tc.subscribe("#", 2)
    var pubRecHeader: Option[MqttPubReplyMessageVariableHeader] = None
    var pubCompHeader: Option[MqttPubReplyMessageVariableHeader] = None
    c.onPubRecRecieve { h =>
      pubRecHeader shouldBe None
      pubRecHeader = Option(h)
    }
    c.onPubCompRecieve { h =>
      pubCompHeader shouldBe None
      pubCompHeader = Option(h)
    }
    val (msg1Id, pubResult1) = c.publish(topic1, Unpooled.copiedBuffer(message1, StandardCharsets.UTF_8), MqttQoS.EXACTLY_ONCE)
    msg1Id should be > 0 //as it's QoS2

    Await.result(pubResult1, defaultTimeout)
    //By the time pubResult1 resolves, PUBREC and PUBCOMP must already be processed
    pubRecHeader.value
    pubRecHeader.foreach { header =>
      header.messageId() shouldBe msg1Id
      header.reasonCode() shouldBe MqttPubReplyMessageVariableHeader.REASON_CODE_OK
    }
    pubCompHeader.value
    pubCompHeader.foreach { header =>
      header.messageId() shouldBe msg1Id
      header.reasonCode() shouldBe MqttPubReplyMessageVariableHeader.REASON_CODE_OK
    }
    val msg = tp.expectSingleMessage()
    msg.topic shouldBe topic1
    msg.stringPayload shouldBe message1
    msg.message.getQos shouldBe 2

    tc.disconnect()
    c.disconnect()
  }

  it should "re-publish QoS2 if client re-connects" in {
    val topic1 = "topicF1"
    val message1 = "message1"

    val c = connect("testF1", cleanSession = false)
    val (tc, tp) = connectPaho()

    tc.subscribe("#", 2)
    var pubRecHeader: Option[MqttPubReplyMessageVariableHeader] = None
    var pubCompHeader: Option[MqttPubReplyMessageVariableHeader] = None
    c.onPubRecRecieve { h =>
      pubRecHeader shouldBe None
      pubRecHeader = Option(h)
    }
    c.onPubCompRecieve { h =>
      pubCompHeader shouldBe None
      pubCompHeader = Option(h)
    }
    val (msg1Id, pubResult1) = c.publish(topic1, Unpooled.copiedBuffer(message1, StandardCharsets.UTF_8), MqttQoS.EXACTLY_ONCE)
    Await.result(c.disconnect(), defaultTimeout)

    msg1Id should be > 0 //as it's QoS2
    c.isConnected() shouldBe false

    Await.result(c.connect(false), defaultTimeout)

    Await.result(pubResult1, defaultTimeout)
    //By the time pubResult1 resolves, PUBREC and PUBCOMP must already be processed
    pubRecHeader.value
    pubRecHeader.foreach { header =>
      header.messageId() shouldBe msg1Id
      header.reasonCode() shouldBe MqttPubReplyMessageVariableHeader.REASON_CODE_OK
    }
    pubCompHeader.value
    pubCompHeader.foreach { header =>
      header.messageId() shouldBe msg1Id
      header.reasonCode() shouldBe MqttPubReplyMessageVariableHeader.REASON_CODE_OK
    }
    //TODO Moquette 0.13 doesn't handle QoS2 properly which causes duplicates. Uncomment when newer version becomes available which fixes it.
//    val msg = tp.expectSingleMessage()
//    msg.topic shouldBe topic1
//    msg.stringPayload shouldBe message1
//    msg.message.getQos shouldBe 2

    tc.disconnect()
    c.disconnect()
  }

  it should "subscribe with QoS0" in {
    val topic1 = "topicG1"
    val topic2 = "topicG2"
    val topic3 = "topicG3"
    val invalidPattern1 = "#/#"
    val message1 = "message1"
    val message2 = "message2"
    val message3 = "message3"

    val c = connect("testG1")
    val (tc, tp) = connectPaho()

    val receivedPublishMessages = new AtomicReference(Seq[MqttPublishMessage]())
    val incomingPublishMessages = new AtomicReference(Seq[MqttPublishMessage]())

    c.onPublishRecieve { pub =>
      receivedPublishMessages.updateAndGet { prevMessages => prevMessages :+ pub }
    }

    c.onIncomingPublish { pub =>
      incomingPublishMessages.updateAndGet { prevMessages => prevMessages :+ pub }
      ReferenceCountUtil.retain(pub)
      Future.successful(())
    }

    //TODO adapt for Mosquitto - it ignores SUBSCRIBE message with the invalid topic patterns
    val subResult = Await.result(c.subscribe(Seq(
      MqttTopicSubscription(topic1, MqttSubscriptionOption.onlyFromQos(MqttQoS.AT_MOST_ONCE)),
      MqttTopicSubscription(topic3, MqttSubscriptionOption.onlyFromQos(MqttQoS.AT_MOST_ONCE)))), defaultTimeout)
    subResult shouldBe Seq(0, 0)

    tc.publish(topic1, message1.getBytes(StandardCharsets.UTF_8), 0, false)
    tc.publish(topic2, message2.getBytes(StandardCharsets.UTF_8), 0, false)
    tc.publish(topic3, message3.getBytes(StandardCharsets.UTF_8), 0, false)

    eventually {
      receivedPublishMessages.get().map(_.payload().toString(StandardCharsets.UTF_8)) should contain theSameElementsAs Seq(message1, message3)
    }
    receivedPublishMessages.get().map(_.fixedHeader().qosLevel()) should contain only (MqttQoS.AT_MOST_ONCE)

    incomingPublishMessages.get() shouldBe receivedPublishMessages.get()

    incomingPublishMessages.get().foreach(ReferenceCountUtil.release)

    tc.disconnect()
    c.disconnect()
  }


  it should "subscribe with QoS1" in {
    val topic1 = "topicH1"
    val message1 = "message1"

    val c = connect("testG1", cleanSession = false)
    val (tc, tp) = connectPaho()

    val receivedPublishMessages = new AtomicReference(Seq[MqttPublishMessage]())
    val incomingPublishMessages = new AtomicReference(Seq[MqttPublishMessage]())

    val sentPubAckMessages = new AtomicReference(Seq[MqttPubReplyMessageVariableHeader]())

    val incomingMessageHandlerPromise = Promise[Unit]()

    c.onPublishRecieve { pub =>
      receivedPublishMessages.updateAndGet { prevMessages => prevMessages :+ pub }
    }

    c.onIncomingPublish { pub =>
      incomingPublishMessages.updateAndGet { prevMessages => prevMessages :+ pub }
      ReferenceCountUtil.retain(pub)
      incomingMessageHandlerPromise.future
    }

    c.onPubAckSent { puback =>
      sentPubAckMessages.updateAndGet { prevPubacks => prevPubacks :+ puback }
    }

    val subResult = Await.result(c.subscribe(Seq(
      MqttTopicSubscription(topic1, MqttSubscriptionOption.onlyFromQos(MqttQoS.AT_LEAST_ONCE))
    )), defaultTimeout)
    subResult shouldBe Seq(1)

    tc.publish(topic1, message1.getBytes(StandardCharsets.UTF_8), 1, false)

    //Don't PUBACK yet, 1st message receiving
    eventually {
      receivedPublishMessages.get().map(_.payload().toString(StandardCharsets.UTF_8)) should contain theSameElementsAs Seq(message1)
    }
    receivedPublishMessages.get().map(_.fixedHeader().qosLevel()) should contain only (MqttQoS.AT_LEAST_ONCE)

    incomingPublishMessages.get() shouldBe receivedPublishMessages.get()

    //Reconnect
    Await.result(c.disconnect(), defaultTimeout)
    c.isConnected() shouldBe false
    Await.result(c.connect(false), defaultTimeout)
    c.isConnected() shouldBe true

    //Receive the re-transmitted message, PUBACK it this time
    eventually {
      receivedPublishMessages.get().map(_.payload().toString(StandardCharsets.UTF_8)) should contain theSameElementsAs Seq(message1, message1)
    }
    receivedPublishMessages.get().map(_.fixedHeader().qosLevel()) should contain only (MqttQoS.AT_LEAST_ONCE)
    incomingPublishMessages.get() shouldBe receivedPublishMessages.get()
    incomingMessageHandlerPromise.success(())

    eventually {
      sentPubAckMessages.get().size should be >= 1
    }

    sentPubAckMessages.get().head.messageId() shouldBe receivedPublishMessages.get().last.variableHeader().packetId()

    incomingPublishMessages.get().foreach(ReferenceCountUtil.release)
  }

  it should "subscribe with QoS2" in {
    val topic1 = "topicI1"
    val message1 = "message1"

    val c = connect("testI1", cleanSession = false)
    val (tc, tp) = connectPaho()

    val receivedPublishMessages = new AtomicReference(Seq[MqttPublishMessage]())
    val incomingPublishMessages = new AtomicReference(Seq[MqttPublishMessage]())

    val sentPubRecMessages = new AtomicReference(Seq[MqttPubReplyMessageVariableHeader]())
    val sentPubCompMessages = new AtomicReference(Seq[MqttPubReplyMessageVariableHeader]())

    val incomingMessageHandlerPromise = Promise[Unit]()

    c.onPublishRecieve { pub =>
      ReferenceCountUtil.retain(pub)
      receivedPublishMessages.updateAndGet { prevMessages => prevMessages :+ pub }
    }

    c.onIncomingPublish { pub =>
      incomingPublishMessages.updateAndGet { prevMessages => prevMessages :+ pub }
      ReferenceCountUtil.retain(pub)
      incomingMessageHandlerPromise.future
    }

    c.onPubRecSent { puback =>
      sentPubRecMessages.updateAndGet { prevPubacks => prevPubacks :+ puback }
    }
    c.onPubCompSent { puback =>
      sentPubCompMessages.updateAndGet { prevPubacks => prevPubacks :+ puback }
    }

    val subResult = Await.result(c.subscribe(Seq(
      MqttTopicSubscription(topic1, MqttSubscriptionOption.onlyFromQos(MqttQoS.EXACTLY_ONCE))
    )), defaultTimeout)
    subResult shouldBe Seq(2)

    tc.publish(topic1, message1.getBytes(StandardCharsets.UTF_8), 2, false)

    //Don't PUBREC yet, 1st message receiving
    eventually {
      receivedPublishMessages.get().map(_.payload().toString(StandardCharsets.UTF_8)) should contain theSameElementsAs Seq(message1)
    }
    receivedPublishMessages.get().map(_.fixedHeader().qosLevel()) should contain only (MqttQoS.EXACTLY_ONCE)

    incomingPublishMessages.get() shouldBe receivedPublishMessages.get()

    //Reconnect
    Await.result(c.disconnect(), defaultTimeout)
    c.isConnected() shouldBe false
    Await.result(c.connect(false), defaultTimeout)
    c.isConnected() shouldBe true

    //Receive the re-transmitted message, PUBREC and PUBCOMP it this time
    eventually {
      receivedPublishMessages.get().map(_.payload().toString(StandardCharsets.UTF_8)) should contain theSameElementsAs Seq(message1, message1)
    }
    receivedPublishMessages.get().map(_.fixedHeader().qosLevel()) should contain only (MqttQoS.EXACTLY_ONCE)
    //no duplicates allowed
    incomingPublishMessages.get().size shouldBe 1

    //mark message processing as finished
    incomingMessageHandlerPromise.success(())

    eventually {
      sentPubRecMessages.get().size should be >= 1
    }
    sentPubRecMessages.get().head.messageId() shouldBe receivedPublishMessages.get().last.variableHeader().packetId()

    eventually {
      sentPubCompMessages.get().size should be >= 1
    }
    sentPubCompMessages.get().head.messageId() shouldBe receivedPublishMessages.get().last.variableHeader().packetId()

    receivedPublishMessages.get().foreach(ReferenceCountUtil.release)
    incomingPublishMessages.get().foreach(ReferenceCountUtil.release)
  }
}
