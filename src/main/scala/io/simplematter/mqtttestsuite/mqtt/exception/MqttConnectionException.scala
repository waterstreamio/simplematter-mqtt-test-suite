package io.simplematter.mqtttestsuite.mqtt.exception

import io.netty.handler.codec.mqtt.MqttConnectReturnCode

class MqttConnectionException(code: MqttConnectReturnCode) extends Exception(s"Connection failed with code $code") {

}
