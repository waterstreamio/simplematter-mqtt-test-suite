package io.simplematter.mqtttestsuite.mqtt

import io.netty.bootstrap.Bootstrap

import scala.util.Try
import scala.util.Failure
import scala.concurrent.{Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*
import io.netty.handler.codec.mqtt.MqttConnAckMessage

import java.util.regex.Pattern
import io.netty.buffer.{ByteBuf, ByteBufUtil, Unpooled}
import io.netty.channel.{Channel, ChannelDuplexHandler, ChannelFuture, ChannelHandlerContext, ChannelInitializer, ChannelOption, ChannelPipeline, ChannelPromise, EventLoopGroup}
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.DecoderResult
import io.netty.handler.codec.mqtt.MqttQoS.AT_MOST_ONCE
import io.netty.handler.codec.mqtt.*
import io.netty.handler.timeout.IdleState
import io.netty.handler.timeout.IdleStateEvent
import io.netty.handler.timeout.IdleStateHandler
import io.netty.util.ReferenceCountUtil
import io.netty.util.concurrent.GenericFutureListener
import io.simplematter.mqtttestsuite.mqtt.exception.{AttemptsExceededException, ClientNotConnectedException, MqttConnectionException, MqttGenericException, TopicNameException}
import org.slf4j.LoggerFactory
import org.slf4j.Logger

import java.net.SocketAddress
import java.nio.charset.{Charset, StandardCharsets}
import java.util.concurrent.{ConcurrentHashMap, ConcurrentMap, ConcurrentSkipListSet, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicLong, AtomicReference}

//import collection.convert.ImplicitConversions.collection.AsScalaIterable
import scala.jdk.CollectionConverters.*


class MqttClient(host: String, port: Int, options: MqttOptions = MqttOptions()) {
  import MqttClient._
  import io.simplematter.mqtttestsuite.util.logExceptions

  private val messageIdsInUse: ConcurrentSkipListSet[Int] = new ConcurrentSkipListSet[Int]()
  private val messageIdCounter: AtomicLong = AtomicLong(0)

  private val channelRef: AtomicReference[Channel] = AtomicReference[Channel]()

  private val connectedFlag: AtomicBoolean = AtomicBoolean(false)
  private var disconnectPromise = Promise[Unit]()

  private val pubackPromises: ConcurrentMap[Int, Promise[Unit]] = new ConcurrentHashMap[Int, Promise[Unit]]()
  private val pubrecPromises: ConcurrentMap[Int, Promise[Unit]] = new ConcurrentHashMap[Int, Promise[Unit]]()
  private val pubcompPromises: ConcurrentMap[Int, Promise[Unit]] = new ConcurrentHashMap[Int, Promise[Unit]]()
  private val pendingPublish: ConcurrentMap[Int, MqttPublishMessage] = new ConcurrentHashMap[Int, MqttPublishMessage]()

  //messageId -> attempts
  private val retransmissionAttempts: ConcurrentMap[Int, Int] = new ConcurrentHashMap[Int, Int]()

  private val subackPromises: ConcurrentMap[Int, Promise[MqttSubAckMessage]] = new ConcurrentHashMap[Int, Promise[MqttSubAckMessage]]()


  //IDs of the QoS2 messages that were processed, but not released yet
  private val qos2ProcessedMessages: ConcurrentSkipListSet[Int] = new ConcurrentSkipListSet[Int]()

  private var closeHandler: Boolean => Unit = (_) => {}

  private var pubAckReceiveHandler: (MqttPubReplyMessageVariableHeader) => Unit = (_) => {}

  private var pubRecReceiveHandler: (MqttPubReplyMessageVariableHeader) => Unit = (_) => {}
  private var pubRelReceiveHandler: (MqttPubReplyMessageVariableHeader) => Unit = (_) => {}
  private var pubCompReceiveHandler: (MqttPubReplyMessageVariableHeader) => Unit = (_) => {}

  private var publishReceiveHandler: (MqttPublishMessage) => Unit = (_) => {}
  private var incomingPublishHandler: (MqttPublishMessage) => Future[Unit] = (_) => { Future.successful(()) }

  private var pubAckSentHandler: (MqttPubReplyMessageVariableHeader) => Unit = (_) => {}
  private var pubRecSentHandler: (MqttPubReplyMessageVariableHeader) => Unit = (_) => {}
  private var pubCompSentHandler: (MqttPubReplyMessageVariableHeader) => Unit = (_) => {}

  private var publishRetransmitHandler: (MqttPublishMessage) => Unit = (_) => {}
  private var pubrelRetransmitHandler: (Int) => Unit = (_) => {}

  private var exceptionHandler: Option[Throwable => Unit] = None

  def connect(cleanSession: Boolean = true): Future[MqttConnAckMessage] = {
    log.debug("Client {} connecting to {}:{}", options.clientId, host, port)

    val connAckPromise = Promise[MqttConnAckMessage]()

    (for {
      _ <- disconnect()
      channel <- establishChannel(connAckPromise)
      _ <- Future { sendConnect(channel, cleanSession) }
      connAck <- connAckPromise.future
      pubrelRedeliveryFuture = retransmitPendingPubrel()
      publishRedeliveryFuture = retransmitPendingMessages()
      _ <- pubrelRedeliveryFuture.zip(publishRedeliveryFuture)
      //Client users should know when redeliveries complete, because publishing new messages concurrently with the
      //redelivery may result in duplicate sending of QoS 2 when the messageId has already been released by MQTT broker
      _ = connectedFlag.set(true)
    } yield {
      disconnectPromise = Promise[Unit]
      connAck
    }).recoverWith { case e: Exception =>
        log.info(s"Connection failed for client ${options.clientId}", e)
        channelRef.updateAndGet {c =>
          if(c != null) c.close()
          null
        }
        Future.failed(e)
    }
  }

  private def establishChannel(connAckPromise: Promise[MqttConnAckMessage]): Future[Channel] = {
    val b = new Bootstrap
    b.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, options.connectTimeoutMillis)
    b.group(nettyLoopGroup)
    b.channel(classOf[NioSocketChannel])
    b.handler(new ChannelInitializer[SocketChannel]() {
      @throws[Exception]
      override protected def initChannel(ch: SocketChannel): Unit = {
        ch.pipeline.addLast("mqttEncoder", MqttEncoder.INSTANCE)
        ch.pipeline.addLast("mqttDecoder", new MqttDecoder(options.maxMessageSize))
        //Triggers IdleStateEvent (which in turn triggers ping) either on read or write packets missing for keepAliveTimeSeconds
        ch.pipeline.addLast("idleStateHandler", new IdleStateHandler(options.keepAliveTimeSeconds, options.keepAliveTimeSeconds, 0, TimeUnit.SECONDS))
        ch.pipeline().addLast("keepAliveHandler", new ChannelDuplexHandler() {
          override def userEventTriggered(ctx: ChannelHandlerContext, evt: Any): Unit = {
            if (evt.isInstanceOf[IdleStateEvent]) {
              log.debug("Channel idle, sending ping: {}", options.clientId)
              ping()
            }
          }
        })
        ch.pipeline.addLast("handler", new ChannelHandler(connAckPromise))
      }
    })
    val connectPromise = Promise[Channel]()
    val channelFuture = b.connect(host, port)
    channelFuture.addListener({ (result: io.netty.util.concurrent.Future[_ >: Void]) =>
      if(result.isSuccess()) {
        log.info("Channel established successfully")
        channelRef.updateAndGet { prevChannel =>
          if(prevChannel != null) prevChannel.close()
          channelFuture.channel()
        }
        connectPromise.success(channelFuture.channel())
      } else
        log.error("Channel establishment failed", result.cause())
        connectPromise.failure(result.cause())
    })
    connectPromise.future
  }

  private def fixedHeader(messageType: MqttMessageType, isDup: Boolean = false, qos: MqttQoS = AT_MOST_ONCE, isRetain: Boolean = false): MqttFixedHeader =
    new MqttFixedHeader(messageType, isDup, qos, isRetain, 0);

  private def sendConnect(channel: Channel, cleanSession: Boolean = true): Unit = {
    val fHeader = fixedHeader(MqttMessageType.CONNECT)
    val vHeader = new MqttConnectVariableHeader(
      options.protocolVersion.protocolName(),
      options.protocolVersion.protocolLevel(),
      options.hasUsername,
      options.hasPassword,
      options.isWillRetain,
      options.willQoS,
      options.will.isDefined,
      cleanSession,
      options.keepAliveTimeSeconds
    );
    val payload = new MqttConnectPayload(
      options.clientId.fold("")(_.value),
      options.willTopic,
      options.will.map(_.body).orNull,
      options.username.orNull,
      options.password.map(_.getBytes(StandardCharsets.UTF_8)).orNull
    );

    channel.writeAndFlush(MqttMessageFactory.newMessage(fHeader, vHeader, payload))
  }

  private def nextMessageId(): Int = {
    for(attempt <- 1 to 1000) {
      val messageId = (messageIdCounter.getAndIncrement() % MAX_MESSAGE_ID).toInt + 1
      if(messageIdsInUse.add(messageId))
        return messageId
    }
    throw new AttemptsExceededException("Exceeded attempts for assigning messageId")
  }

  private def releaseMessageId(messageId: Int): Boolean = {
    messageIdsInUse.remove(messageId)
  }

  def ping(): Unit = {
    getChannel().fold {
      log.info("Can't ping, no channel available")
    } {
      _.writeAndFlush(MqttMessageFactory.newMessage(fixedHeader(MqttMessageType.PINGREQ), null, null))
    }
  }

  def disconnect(): Future[Unit] = {
    getChannel().fold {
      disconnectPromise.trySuccess(())
    } { channel =>
      channel.writeAndFlush(MqttMessageFactory.newMessage(fixedHeader(MqttMessageType.DISCONNECT), null, null))
    }
    disconnectPromise.future
  }

  def dropConnection(): Future[Unit] = {
    getChannel().fold {
      disconnectPromise.trySuccess(())
    } { channel =>
      channel.close()
    }
    disconnectPromise.future
  }

  private def getChannel(): Option[Channel] = Option(channelRef.get())

  def isConnected(): Boolean = {
    connectedFlag.get() == true
  }

  def onClose(closeHandler: Boolean => Unit): Unit = {
    this.closeHandler = closeHandler
  }

  def onException(exceptionHandler: Throwable => Unit): Unit = {
    this.exceptionHandler = Option(exceptionHandler)
  }

  /**
   * Handler for the message send errors
   * @param messageSendFailureHandler
   */
  //  def onMessageSendFailure(messageSendFailureHandler: (MqttMessageType) => Unit): Unit = {
  //    ???
  //  }

  def onPubAckRecieve(handler: (MqttPubReplyMessageVariableHeader) => Unit): Unit = {
    this.pubAckReceiveHandler = handler
  }

  /**
   * When PUBACK is successfully sent
   *
   * @param handler
   */
  def onPubAckSent(handler: (MqttPubReplyMessageVariableHeader) => Unit): Unit = {
    this.pubAckSentHandler = handler
  }

  /**
   * When PUBREC is successfully sent
   *
   * @param handler
   */
  def onPubRecSent(handler: (MqttPubReplyMessageVariableHeader) => Unit): Unit = {
    this.pubRecSentHandler = handler
  }

  /**
   * When PUBREL is successfully sent
   *
   * @param handler
   */
  def onPubCompSent(handler: (MqttPubReplyMessageVariableHeader) => Unit): Unit = {
    this.pubCompSentHandler = handler
  }

  def onPubRecRecieve(handler: (MqttPubReplyMessageVariableHeader) => Unit): Unit = {
    this.pubRecReceiveHandler = handler
  }

  def onPubRelRecieve(handler: (MqttPubReplyMessageVariableHeader) => Unit): Unit = {
    this.pubRelReceiveHandler = handler
  }

  def onPubCompRecieve(handler: (MqttPubReplyMessageVariableHeader) => Unit): Unit = {
    this.pubCompReceiveHandler = handler
  }

  def onPublishRecieve(handler: (MqttPublishMessage) => Unit): Unit = {
    this.publishReceiveHandler = handler
  }

  def onPublishRetransmit(handler: (MqttPublishMessage) => Unit): Unit = {
    this.publishRetransmitHandler = handler
  }

  def onPubrelRetransmit(handler: (Int) => Unit): Unit = {
    this.pubrelRetransmitHandler = handler
  }

  /**
   * Unlike {@link onPublishRecieve()}, de-duplicates the PUBLISH message (for QoS2),
   * makes the client wait for the handler's Future resolving before PUBACK (for QoS1)
   * or PUBREC (for QoS2).
   *
   * For QoS0 and QoS1 calls the {@code handler} each time a PUBLISH message arrives.
   * For QoS2 calls the {@code handler} only once per each {@code messageId}, until that
   * {@code messageId} gets released by PUBREL from the server.
   * If the future returned by {@code handler} fails, the client logs that but still confirms the message
   * in accordance with MQTT spec 3.1.1. In future when the client supports MQTT 5.0 it will
   * send PUBACK or PUBREC with the failure code.
   *
   * A message won't be released until Future resolves.
   *
   *
   * @param handler
   */
  def onIncomingPublish(handler: (MqttPublishMessage) => Future[Unit]): Unit = {
    this.incomingPublishHandler = handler
  }

  /**
   * Publishes the message and checks for confirmation.
   * Retries to re-transmit on re-connect if needed.
   * Returned future is resolved when all the subsequent messages and retries are processed -
   * i.e. for QoS0 it resolves when message is sent over the TCP, for QoS1 - when PUBACK is received (retrying PUBLISH if needed),
   * for QoS2 - when PUBCOMP is received (retrying PUBLISH and PUBREL if needed).
   * If client gets disconnected before message gets confirmed it waits for the re-connect, then re-delivers
   * the message and waits for the confirmation.
   *
   * @param message
   * @return pair: messageId and Future that completes when message publishing fully completes, with confirmation
   */
  def publish(topic: String, payload: ByteBuf, qos: MqttQoS, retain: Boolean = false, label: Option[String] = None): (Int, Future[Unit]) = {
    if(!isTopicNameValid(topic)) {
      payload.release()
      return (0, Future.failed(new TopicNameException(topic)))
    }

    val messageId = if (qos == MqttQoS.AT_MOST_ONCE) 0 else nextMessageId()
    val variableHeader = new MqttPublishVariableHeader(topic, messageId)
    val message = new MqttPublishMessage(
      fixedHeader(MqttMessageType.PUBLISH, qos = qos, isRetain = retain, isDup = false),
      variableHeader,
      payload
    )
    resetRetransmissionAttempts(messageId);

    qos match {
      case MqttQoS.AT_LEAST_ONCE =>
        rememberPendingPublish(messageId, message, label)
        val pubackFuture = waitForPuback(messageId)
        sendPublishMessage(message).logExceptions("PUBLISH QoS1 error", log)
        (messageId, pubackFuture)
      case MqttQoS.EXACTLY_ONCE =>
        rememberPendingPublish(messageId, message, label)
        val completionFuture = waitQos2Workflow(messageId)
        sendPublishMessage(message).logExceptions("PUBLISH QoS2 error", log)
        (messageId, completionFuture)
      case _ =>
        (messageId, sendPublishMessage(message))
    }
  }

  /**
   * Number of QoS 1 or 2 messages for which PUBLISH has been sent but we haven't yet received
   * complete confirmation from the server
   *
   * @return in-flight messages count
   */
  def publishInFlightCount(): Int = {
    pendingPublish.size() + pubcompPromises.size()
  }


  def subscribe(subscriptions: Seq[MqttTopicSubscription]): Future[Seq[Int]] = {
    val messageId = nextMessageId()

    val subAckPromise = Promise[MqttSubAckMessage]()
    Option(subackPromises.put(messageId, subAckPromise)).foreach { prevPromise =>
      log.warn("messageId collision in SUBACK promises map {}", messageId)
      prevPromise.failure(AssertionError("messageId collision in SUBACK promises map"))
    }

    val subCompletionFuture: Future[Seq[Int]] = (for (
      _ <- sendSubscribe(messageId, subscriptions);
      subAck <- subAckPromise.future
    ) yield subAck).map(_.payload().reasonCodes().asScala.map(_.toInt).toSeq)

    subCompletionFuture.onComplete { result =>
      result match {
        case Failure(exception) if !subAckPromise.isCompleted =>
          subAckPromise.failure(new MqttGenericException("SUBSCRIBE failed", exception))
        case _ => //nop
      }
      releaseMessageId(messageId)
    }

    subCompletionFuture
  }

  private def sendSubscribe(messageId: Int, subscriptions: Seq[MqttTopicSubscription]): Future[Unit] = {
    getChannel().fold {
      Future.failed(new ClientNotConnectedException("No channel to server"))
    } { channel =>
      val message = MqttMessageFactory.newMessage(
        fixedHeader(MqttMessageType.SUBSCRIBE, qos = MqttQoS.AT_LEAST_ONCE),
        new MqttMessageIdAndPropertiesVariableHeader(messageId, MqttProperties.NO_PROPERTIES),
        new MqttSubscribePayload(subscriptions.asJava))
      val channelPromise = channel.newPromise()
      channel.writeAndFlush(message, channelPromise)
      promiseChannelToScala(channelPromise).future
    }
  }


  private def waitForPuback(messageId: Int): Future[Unit] = {
    val pubackPromise = Promise[Unit]()
    val prevPromise = pubackPromises.put(messageId, pubackPromise)
    if(prevPromise != null) {
      log.warn("messageId collision in PUBACK promises map {}", messageId)
      prevPromise.failure(AssertionError("messageId collision in PUBACK promises map"))
    }

    pubackPromise.future.onComplete {_ => forgetPendingPublish(messageId) }

    pubackPromise.future
  }


  private def waitQos2Workflow(messageId: Int): Future[Unit] = {
    val pubrecFuture = waitForPubrec(messageId)
    val completionFuture = for {
      _ <- pubrecFuture
      _ = sendPubrel(messageId).logExceptions(s"PUBREL error for client ${options.clientId}", log)
      _ = resetRetransmissionAttempts(messageId)
      _ <- waitForPubcomp(messageId)
    } yield ()
    completionFuture
  }

  private def waitForPubrec(messageId: Int): Future[Unit] = {
    val pubrecPromise = Promise[Unit]()
    val prevPubrecPromise = pubrecPromises.put(messageId, pubrecPromise)
    if(prevPubrecPromise != null) {
      log.warn("messageId collision in PUBREC promises map {}", messageId)
      prevPubrecPromise.failure(AssertionError("messageId collision in PUBREC promises map"))
    }
    pubrecPromise.future.onComplete {_ => forgetPendingPublish(messageId) }
    pubrecPromise.future
  }


  private def waitForPubcomp(messageId: Int): Future[Unit] = {
    val pubcompPromise = Promise[Unit]()
    val prevPubcompPromise = pubcompPromises.put(messageId, pubcompPromise)
    if(prevPubcompPromise != null) {
      log.warn("messageId collision in PUBCOMP promises map {}", messageId)
      prevPubcompPromise.failure(AssertionError("messageId collision in PUBCOMP promises map"))
    }
    pubcompPromise.future
  }

  private def sendPuback(messageId: Int): Future[Unit] = {
    val varHeader = new MqttPubReplyMessageVariableHeader(messageId, MqttPubReplyMessageVariableHeader.REASON_CODE_OK, MqttProperties.NO_PROPERTIES)
    val message = new MqttPubAckMessage(fixedHeader(MqttMessageType.PUBACK), varHeader)
    writeAndPromise(message)
      .map { _ => pubAckSentHandler(varHeader) }
  }

  private def sendPubrec(messageId: Int): Future[Unit] = {
    val varHeader = new MqttPubReplyMessageVariableHeader(messageId, MqttPubReplyMessageVariableHeader.REASON_CODE_OK, MqttProperties.NO_PROPERTIES)
    writeAndPromise(MqttMessageFactory.newMessage(
      fixedHeader(MqttMessageType.PUBREC), varHeader, null))
      .map { _ => pubRecSentHandler(varHeader) }
  }

  private def sendPubrel(messageId: Int): Future[Unit] = {
//    pubrelSentIds.add(messageId)
    writeAndPromise(MqttMessageFactory.newMessage(
      fixedHeader(MqttMessageType.PUBREL, qos = MqttQoS.AT_LEAST_ONCE),
      new MqttPubReplyMessageVariableHeader(messageId, MqttPubReplyMessageVariableHeader.REASON_CODE_OK, MqttProperties.NO_PROPERTIES),
      null))
  }

  private def sendPubcomp(messageId: Int): Future[Unit] = {
    val varHeader = new MqttPubReplyMessageVariableHeader(messageId, MqttPubReplyMessageVariableHeader.REASON_CODE_OK, MqttProperties.NO_PROPERTIES)
    writeAndPromise(MqttMessageFactory.newMessage(
      fixedHeader(MqttMessageType.PUBCOMP), varHeader, null))
      .map { _ => pubCompSentHandler(varHeader) }
  }

  private def rememberPendingPublish(messageId: Int, message: MqttPublishMessage, label: Option[String]): Unit = {
    message.retain() //Increase reference count to prevent message cleanup
    Option(pendingPublish.put(messageId, message)).foreach { prevMessage =>
      log.warn("messageId collision in pendingPublish map {}", messageId)
      prevMessage.release() //Decrease reference count to enable message cleanup
    }
  }

  private def forgetPendingPublish(messageId: Int): Unit = {
    Option(pendingPublish.remove(messageId)).foreach { message =>
      releaseMessageId(messageId)
      message.release() //Decrease reference count to enable message cleanup
      log.debug("Message {} removed from pendingPublish", messageId)
    }
  }

  private def resetRetransmissionAttempts(messageId: Int): Unit = {
    retransmissionAttempts.remove(messageId)
  }

  private def retransmissionAttempt(messageId: Int): Int = {
    retransmissionAttempts.compute(messageId, { (_: Int, prevAttempts: Int | Null) =>
      prevAttempts match {
        case null =>
          options.maxRetransmissionAttempts
        case 0 =>
          0
        case a: Int =>
          a - 1
      }
    })
  }

  private def failOutboundMessagePermanently(messageId: Int, notice: String): Unit = {
    log.info("Outbound message {} in client {} failed permanently: {}", messageId, options.clientId, notice)
    forgetPendingPublish(messageId)
    val exception = new MqttGenericException(s"Outbound message ${messageId} in client ${options.clientId} failed permanently: $notice")
    Option(pubackPromises.remove(messageId)).foreach(_.failure(exception))
    Option(pubrecPromises.remove(messageId)).foreach(_.failure(exception))
    Option(pubcompPromises.remove(messageId)).foreach(_.failure(exception))
  }

  private def retransmitPendingMessages(): Future[Unit] = {
    if(log.isDebugEnabled) {
      log.debug("Client {} messages to retransmit: count={}, ids={}", options.clientId, pendingPublish.size(), pendingPublish.keySet().asScala)
    }
    val retransmissionFutures: Iterable[Future[Unit]] = pendingPublish.values().asScala.map { (message: MqttPublishMessage) =>
      val messageId = message.variableHeader().packetId()
      if(retransmissionAttempt(messageId) > 0) {
        log.debug("Redelivering PUBLISH message id={}, qos={}, topic={}",
          messageId,
          message.fixedHeader().qosLevel(),
          message.variableHeader().topicName()
        )
        val dupMsg = dup(message)
        publishRetransmitHandler(dupMsg)
        sendPublishMessage(dupMsg)
      } else {
        failOutboundMessagePermanently(messageId, "retransmission attempts exceeded")
        resetRetransmissionAttempts(messageId)
        //redelivery of other messages still goes on unaffected
        Future.successful(())
      }
    }
    Future.sequence(retransmissionFutures).map { _ => () }
  }

  private def retransmitPendingPubrel(): Future[Unit] = {
    log.debug("Client {} PUBRELs to retransmit: {}", options.clientId, pubcompPromises.size())
    val retransmissionFutures = pubcompPromises.keySet().asScala.map { (messageId: Int) =>
      if(retransmissionAttempt(messageId) > 0) {
        log.debug("Redelivering PUBREL message id={}", messageId)
        pubrelRetransmitHandler(messageId)
        sendPubrel(messageId)
      } else {
        failOutboundMessagePermanently(messageId, "retransmission attempts exceeded")
        resetRetransmissionAttempts(messageId)
        //redelivery of other messages still goes on unaffected
        Future.successful(())
      }
    }
    Future.sequence(retransmissionFutures).map { _ => () }
  }

  private def sendPublishMessage(message: MqttPublishMessage): Future[Unit] = {
    getChannel().fold {
      Future.failed(new ClientNotConnectedException("No channel to server"))
    } { channel =>
      log.debug("Sending PUBLISH {} {}, {}", options.clientId, message.variableHeader().packetId(), message.variableHeader().topicName())
      val channelPromise = channel.newPromise()
      channel.writeAndFlush(message, channelPromise)
      promiseChannelToScala(channelPromise).future
    }
  }


  private def handleClose(wasAccepted: Boolean): Unit = {
    closeHandler(wasAccepted)
  }

  private def handlePuback(pubReplyVariableHeader: MqttPubReplyMessageVariableHeader): Unit = {
    pubAckReceiveHandler(pubReplyVariableHeader)
    val promiseOpt = Option(pubackPromises.remove(pubReplyVariableHeader.messageId()))
    //0x80 and higher -> error. Byte casted to Int becomes negative in this case.
    if(pubReplyVariableHeader.reasonCode() >= 0) {
      promiseOpt.foreach { _.success(()) }
    } else {
      promiseOpt.foreach { _.failure(new MqttGenericException(s"PUBACK with unsuccessful reason code ${pubReplyVariableHeader.reasonCode()}")) }
    }
  }


  private def handlePubrec(pubReplyVariableHeader: MqttPubReplyMessageVariableHeader): Unit = {
    pubRecReceiveHandler(pubReplyVariableHeader)
    val promiseOpt = Option(pubrecPromises.remove(pubReplyVariableHeader.messageId()))
    //0x80 and higher -> error. Byte casted to Int becomes negative in this case.
    if(pubReplyVariableHeader.reasonCode() >= 0) {
      promiseOpt.foreach { _.success(()) }
    } else {
      promiseOpt.foreach { _.failure(new MqttGenericException(s"PUBREC with unsuccessful reason code ${pubReplyVariableHeader.reasonCode()}")) }
    }
  }

  private def handlePubrel(pubReplyMessageVariableHeader: MqttPubReplyMessageVariableHeader): Unit = {
    pubRelReceiveHandler(pubReplyMessageVariableHeader)
    val messageId = pubReplyMessageVariableHeader.messageId()
    qos2ProcessedMessages.remove(messageId)
    sendPubcomp(messageId).logExceptions(s"PUBCOMP error messageId=${messageId}", log)
  }

  private def handlePubcomp(pubReplyVariableHeader: MqttPubReplyMessageVariableHeader): Unit = {
    pubCompReceiveHandler(pubReplyVariableHeader)
    val promiseOpt = Option(pubcompPromises.remove(pubReplyVariableHeader.messageId()))
    promiseOpt.foreach { _.success(()) }
    //0x80 and higher -> error. Byte casted to Int becomes negative in this case. However, we don't fail the promise because by now there's nothing to re-transmit.
    if(pubReplyVariableHeader.reasonCode() < 0) {
      log.error(s"PUBCOMP with unsuccessful reason code ${pubReplyVariableHeader.reasonCode()}")
    }
  }

  private def handleSubAck(subAck: MqttSubAckMessage): Unit = {
    Option(subackPromises.remove(subAck.variableHeader().messageId())).foreach { _.success(subAck) }
  }

  private def handlePublish(message: MqttPublishMessage): Unit = {
    publishReceiveHandler(message)
    val messageId = message.variableHeader().packetId()
    val needsProcessing = message.fixedHeader().qosLevel() != MqttQoS.EXACTLY_ONCE || qos2ProcessedMessages.add(messageId)
    if(needsProcessing) {
      val handlingFuture = incomingPublishHandler(message)
      handlingFuture.onComplete { result =>
        result match {
          case Failure(exception) => log.warn(s"Message processing failed messageId=${messageId}, topic=${message.variableHeader().topicName()}, clientId=${options.clientId}", exception)
          case _ => //nop
        }
        message.fixedHeader().qosLevel() match {
          case MqttQoS.AT_LEAST_ONCE =>
            //TODO when we support MQTT 5.0 in this client - also report success/failure code
            sendPuback(messageId).logExceptions(s"PUBACK error messageId=${messageId}", log)
          case MqttQoS.EXACTLY_ONCE =>
            sendPubrec(messageId).logExceptions(s"PUBREC error messageId=${messageId}", log)
          case _ => //nop
        }
        ReferenceCountUtil.release(message)
      }
    }
  }

  private def writeAndPromise(message: MqttMessage): Future[Unit] = {
    getChannel().fold {
      Future.failed(new ClientNotConnectedException("No channel to server"))
    } { channel =>
      val channelPromise = channel.newPromise()
      channel.writeAndFlush(message, channelPromise)
      promiseChannelToScala(channelPromise).future
    }
  }

  class ChannelHandler(connAckSuccessPromise: Promise[MqttConnAckMessage]) extends ChannelDuplexHandler() {
    override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = {
      exceptionHandler.fold {
        log.error("Exception in MQTT client", cause)
      } {
        _(cause)
      }
    }

    override def connect(ctx: ChannelHandlerContext, remoteAddress: SocketAddress, localAddress: SocketAddress, promise: ChannelPromise): Unit = {
      super.connect(ctx, remoteAddress, localAddress, promise)
    }

    override def channelInactive(ctx: ChannelHandlerContext): Unit = {
      log.debug("Channel inactive: clientId={}", options.clientId)
      val wasAccepted = connectedFlag.getAndSet(false)
      channelRef.set(null)
      disconnectPromise.trySuccess(())
      handleClose(wasAccepted)
    }

    override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = {
      validateMqttMessage(ctx, msg).foreach { mqttMessage =>
        mqttMessage.fixedHeader().messageType() match {
          case MqttMessageType.CONNACK =>
            log.debug("Received CONNACK {}", options.clientId)
            //TBD do we need to detect duplicate ConnAcks?
            val connAck = mqttMessage.asInstanceOf[MqttConnAckMessage]

            if (connAck.variableHeader().connectReturnCode() == MqttConnectReturnCode.CONNECTION_ACCEPTED) {
              connAckSuccessPromise.success(connAck)
            } else {
              connAckSuccessPromise.failure(new MqttConnectionException(connAck.variableHeader().connectReturnCode()))
            }
          case MqttMessageType.PINGRESP =>
            log.debug("Received PINGRESP {}", options.clientId)
          case MqttMessageType.PUBACK =>
            val pubAck = mqttMessage.asInstanceOf[MqttPubAckMessage]
            log.debug("Received PUBACK {} {}", options.clientId, pubAck.variableHeader().messageId())
            pubAck.variableHeader() match {
              case pubReplyHeader: MqttPubReplyMessageVariableHeader => handlePuback(pubReplyHeader)
              case other => handlePuback(new MqttPubReplyMessageVariableHeader(other.messageId(), MqttPubReplyMessageVariableHeader.REASON_CODE_OK, MqttProperties.NO_PROPERTIES))
            }
          case MqttMessageType.PUBREC =>
            mqttMessage.variableHeader() match {
              case pubReplyHeader: MqttPubReplyMessageVariableHeader =>
                log.debug("Received PUBREC {} {}", options.clientId, pubReplyHeader.messageId())
                handlePubrec(pubReplyHeader)
              case other => log.error("Received invalid PUBREC variable header {} {}", options.clientId, other)
            }
          case MqttMessageType.PUBREL =>
            mqttMessage.variableHeader() match {
              case pubReplyHeader: MqttPubReplyMessageVariableHeader =>
                log.debug("Received PUBREL {} {}", options.clientId, pubReplyHeader.messageId())
                handlePubrel(pubReplyHeader)
              case other => log.error("Received unsupported PUBREL variable header {}", other)
            }
          case MqttMessageType.PUBCOMP =>
            mqttMessage.variableHeader() match {
              case pubReplyHeader: MqttPubReplyMessageVariableHeader =>
                log.debug("Received PUBCOMP {} {}", options.clientId, pubReplyHeader.messageId())
                handlePubcomp(pubReplyHeader)
              case other => log.error("Received unsupported PUBCOMP variable header {}", other)
            }
          case MqttMessageType.SUBACK =>
            log.debug("Received SUBACK {}", options.clientId)
            handleSubAck(mqttMessage.asInstanceOf[MqttSubAckMessage])
          case MqttMessageType.PUBLISH =>
            val mqttPublishMessage = mqttMessage.asInstanceOf[MqttPublishMessage]
            log.debug("Received PUBLISH {} {}", options.clientId, mqttPublishMessage.variableHeader().packetId())
            ReferenceCountUtil.retain(mqttMessage)
            handlePublish(mqttPublishMessage)
          case otherType =>
            ctx.pipeline.fireExceptionCaught (new Exception ("Unsupported message type " + otherType) )
        }
      }
      ReferenceCountUtil.release(msg)
    }

    private def validateMqttMessage(ctx: ChannelHandlerContext, msg: Any): Option[MqttMessage] = {
      msg match {
        case mqttMessage: MqttMessage =>
          val decoderResult = mqttMessage.decoderResult()
          if(decoderResult.isFailure()) {
            ctx.pipeline.fireExceptionCaught(decoderResult.cause)
            None
          } else if (!decoderResult.isFinished()) {
            ctx.pipeline.fireExceptionCaught(new Exception("Unfinished message"))
            None
          } else {
            Some(mqttMessage)
          }
        case _ =>
          ctx.pipeline.fireExceptionCaught(new Exception("Unexpected message type"))
          None
      }
    }
  }
}


object MqttClient {

  // patterns for topics validation
  private val validTopicNamePattern = Pattern.compile("^[^#+\\u0000]+$")
  private val validTopicFilterPattern = Pattern.compile("^(#|((\\+(?![^/]))?([^#+]*(/\\+(?![^/]))?)*(/#)?))$")

  private val MAX_MESSAGE_ID = 65535
  private val MAX_TOPIC_LEN = 65535
  private val MIN_TOPIC_LEN = 1
  private val DEFAULT_IDLE_TIMEOUT = 0

  private lazy val nettyLoopGroup = new NioEventLoopGroup

  private val log = LoggerFactory.getLogger(classOf[MqttClient])


  def isTopicNameValid(topicName: String): Boolean = {
    val bytesLength = topicName.getBytes("UTF-8").length
    bytesLength >= MIN_TOPIC_LEN &&
      bytesLength <= MAX_TOPIC_LEN &&
      validTopicNamePattern.matcher(topicName).find()
  }

  def dup(message: MqttPublishMessage): MqttPublishMessage = {
    message.retain() //we're sending a copy, make sure that body isn't released just yet
    if(message.fixedHeader().isDup)
      message
    else {
      val h = message.fixedHeader()
      MqttPublishMessage(
        new MqttFixedHeader(h.messageType(), true, h.qosLevel(), h.isRetain, h.remainingLength()),
        message.variableHeader(),
        message.payload()
      )
    }
  }

  def shutdown(): Future[Unit] = {
    log.debug("Shutting down MQTT client")
    futureNettyToScala(nettyLoopGroup.shutdownGracefully()).map(_ => log.debug("MQTT client shutdown complete") )
  }

  private def futureNettyToScala[T](nettyFuture: io.netty.util.concurrent.Future[T]): Future[T] = {
    val p = Promise[T]()
    nettyFuture.addListener(new GenericFutureListener[io.netty.util.concurrent.Future[T]] {
      override def operationComplete(future: io.netty.util.concurrent.Future[T]): Unit = {
        if(future.isSuccess) {
          p.success(future.get())
        } else {
          p.failure(future.cause())
        }
      }
    })
    p.future
  }

  private def promiseChannelToScala(channelPromise: ChannelPromise): Promise[Unit] = {
    val p = Promise[Unit]()
    channelPromise.addListener { (fut: io.netty.util.concurrent.Future[_]) =>
      if(fut.isSuccess)
        p.success(())
      else
        p.failure(fut.cause())
    }
    p
  }

}
