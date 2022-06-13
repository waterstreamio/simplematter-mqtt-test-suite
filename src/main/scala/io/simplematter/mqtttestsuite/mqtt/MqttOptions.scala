package io.simplematter.mqtttestsuite.mqtt

import io.netty.handler.codec.mqtt.MqttVersion
import io.simplematter.mqtttestsuite.model.ClientId

case class MqttOptions(protocolVersion: MqttVersion = MqttVersion.MQTT_3_1_1,
                       username: Option[String] = None,
                       password: Option[String] = None,
                       will: Option[MqttWillMessage] = None,
                       keepAliveTimeSeconds: Int = 30,
                       clientId: Option[ClientId] = None,
                       connectTimeoutMillis: Int = 10000,
                       maxMessageSize: Int = 8*1024,
                       maxRetransmissionAttempts: Int = 5) {

  def hasUsername: Boolean =  username.isDefined

  def hasPassword: Boolean =  password.isDefined

  def isWillRetain: Boolean = will.map(_.retain).getOrElse(false)

  def willQoS: Int = will.map(_.qos).getOrElse(0)

  def willTopic: String = will.map(_.topic).getOrElse("")
}

case class MqttWillMessage(topic: String,
                           body: Array[Byte],
                           retain: Boolean = false,
                           qos: Int = 0)

