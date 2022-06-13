package io.simplematter.mqtttestsuite.mqtt

object MqttUtils {
  def isSubAckError(subAckCode: Int): Boolean = subAckCode >= 0x80

}
