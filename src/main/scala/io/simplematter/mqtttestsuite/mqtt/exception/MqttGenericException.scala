package io.simplematter.mqtttestsuite.mqtt.exception

import io.netty.handler.codec.mqtt.MqttConnectReturnCode

class MqttGenericException(message: String, cause: Throwable | Null = null) extends Exception(message, cause) {

}
