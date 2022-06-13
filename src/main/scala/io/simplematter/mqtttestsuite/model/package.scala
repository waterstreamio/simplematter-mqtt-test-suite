package io.simplematter.mqtttestsuite

import io.simplematter.mqtttestsuite.scenario.ScenarioState
import zio.json.{JsonDecoder, JsonEncoder}
import zio.Task

package object model {
  opaque type NodeId = String

  object NodeId {
    def apply(nodeId: String): NodeId = nodeId

    extension (nodeId: NodeId) {
      def value: String = nodeId
    }
  }

  opaque type ClientId = String

  object ClientId {
    def apply(clientId: String): ClientId = clientId

    extension (clientId: ClientId) {
      def value: String = clientId
    }
  }

  opaque type MqttTopicName = String

  object MqttTopicName {
    def apply(topic: String): MqttTopicName = topic

    extension (topic: MqttTopicName) {
      def value: String = topic
    }
  }

  opaque type MessageId = String

  object MessageId {
    val clientIdDelimiter = "-"

    def apply(clientId: ClientId, index: Int): MessageId = {
      clientId.value + clientIdDelimiter + index
    }

    def fromString(str: String): Task[MessageId] = {
      //for performance reasons, validation is simplified
      if(str.contains(clientIdDelimiter))
        Task.succeed(str)
      else
        Task.fail(IllegalArgumentException(s"MessageId must contain ${clientIdDelimiter}"))
    }

    extension (messageId: MessageId) {
      def value: String = messageId

      def clientId: ClientId = {
        val clientIdEnds = messageId.lastIndexOf("-")
        if(clientIdEnds < 0)
          ClientId(messageId)
        else
          ClientId(messageId.take(clientIdEnds))
      }
    }
  }
}
