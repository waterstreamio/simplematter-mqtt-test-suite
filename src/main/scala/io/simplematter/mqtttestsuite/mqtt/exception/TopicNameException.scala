package io.simplematter.mqtttestsuite.mqtt.exception

class TopicNameException(topicName: String) extends Exception(s"Invalid topic name: $topicName") {

}
