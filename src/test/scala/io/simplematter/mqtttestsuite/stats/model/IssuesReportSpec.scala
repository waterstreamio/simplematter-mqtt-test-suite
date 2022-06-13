package io.simplematter.mqtttestsuite.stats.model

import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import io.simplematter.mqtttestsuite.model.{MessageId, ClientId}

class IssuesReportSpec extends AnyFlatSpec
  with should.Matchers
  with ScalaCheckPropertyChecks
  with TableDrivenPropertyChecks
  with EitherValues {

  "IssuesReport" should "aggregate multiple reports" in {
//    val client1 = ClientId("a")
//    val client1MissingMsg1 = (1 to 5).map(i => MessageId(client1, i))
//    val client1MissingMsg2 = (6 to 5).map(i => MessageId(client1, i))
//    val client2 = ClientId("b")
//    val client3 = ClientId("c")
//    val report1 = IssuesReport(Map(client1 -> ClientIssuesReport()))

  }

  "ClientMessagesReceived" should "build the event from messages" in {
    val client1 = ClientId("a")
    val client2 = ClientId("b")
    val messages1 = (1 to 5).map { i => DeliveredMessage(MessageId(client1, i), "fakeTopic", 1000 + i, client2, 2000 + i)}

    Some(ClientMessagesReceived.build(messages1)).foreach { msgsReceived =>
      msgsReceived.firstReceivingTimestamp shouldBe 2001
      msgsReceived.lastReceivingTimestamp shouldBe 2005
      msgsReceived.head shouldBe messages1.map(_.id)
      msgsReceived.tail shouldBe empty
    }

    val messages2 = (1 to 30).map { i => DeliveredMessage(MessageId(client1, i), "fakeTopic", 1000 + i, client2, 2000 + i)}
    Some(ClientMessagesReceived.build(messages2)).foreach { msgsReceived =>
      msgsReceived.firstReceivingTimestamp shouldBe 2001
      msgsReceived.lastReceivingTimestamp shouldBe 2030
      msgsReceived.head shouldBe messages2.take(10).map(_.id)
      msgsReceived.tail shouldBe messages2.takeRight(10).map(_.id)
    }
  }

  it should "merge the event from messages" in {
    val client1 = ClientId("a")
    val client2 = ClientId("b")
    val client3 = ClientId("c")
    val client4 = ClientId("d")

    val topic1 = "fakeTopic"

    val msg1a = DeliveredMessage(MessageId(client1, 1), topic1, 9000, client2, 10000)
    val msg1z = DeliveredMessage(MessageId(client1, 100), topic1, 19000, client2, 20000)
    val messagesReceived1 = ClientMessagesReceived(20, 10000, 20000, head = Seq(msg1a.id), tail = Seq(msg1z.id))

    val messages1 = (1 to 30).map { i => DeliveredMessage(MessageId(client2, i), topic1, 1000 + i, client3, 2000 + i)}
    Some(messagesReceived1.merge(messages1)).foreach { msgsReceived =>
      msgsReceived.firstReceivingTimestamp shouldBe 2001
      msgsReceived.lastReceivingTimestamp shouldBe 20000
      msgsReceived.head shouldBe messages1.take(10).map(_.id)
      msgsReceived.tail shouldBe messages1.takeRight(9).map(_.id) :+ msg1z.id
    }

    val messages2 = (1 to 30).map { i => DeliveredMessage(MessageId(client3, i), topic1, 10000 + i, client4, 20000 + i)}
    Some(messagesReceived1.merge(messages2)).foreach { msgsReceived =>
      msgsReceived.firstReceivingTimestamp shouldBe 10000
      msgsReceived.lastReceivingTimestamp shouldBe 20030
      msgsReceived.head shouldBe msg1a.id +: messages2.take(9).map(_.id)
      msgsReceived.tail shouldBe messages2.takeRight(10).map(_.id)
    }
  }
}
