package io.simplematter.mqtttestsuite.stats.presentation

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should
import io.simplematter.mqtttestsuite.model.ClientId

class ReportOutputModelSpec extends AnyFlatSpec with should.Matchers {

  "ReportOutput" should "produce plain text for the simple report" in {
    val reportOutput = ReportOutputModel(title = "sample report",
      items = Seq(
        PlainText("first item"),
        EmphasizedText("second item"),
        LinkText("third item", LinkToClientIssues(ClientId("client1")))
      )
    )

    val txt = ReportOutputPlainTextRenderer.render(reportOutput)

    txt.lines().count().toInt should be >= 4
    txt should include("sample report")
    txt should include("first item")
    txt should include("second item")
    txt should include("third item")

    val expectedTxt = (
     """|====================================================================================================
        |==                                         sample report                                          ==
        |====================================================================================================
        ||first item
        ||second item
        ||third item
        |====================================================================================================
        |""".stripMargin)

      txt.trim shouldBe expectedTxt.trim
//      txt.shouldBe("ololo")
  }

  it should "produce plain text for the table" in {
    val reportOutput = ReportOutputModel(title = "sample report",
      items = Seq(
        PlainText("first item"),
        Table(head = Seq(ColumnHead("Ready", Option(10)), ColumnHead("Steady", Option(20)), ColumnHead("GO!!!")),
          body = Seq(
            Seq(PlainText("foo1"), PlainText("bar1"), PlainText("baz1")),
            Seq(PlainText("foo2"), PlainText("bar2"), PlainText("baz2"), PlainText("bzz2")),
            Seq(PlainText("foo3"), PlainText("bar3"))
          )
        )
      )
    )

    val txt = ReportOutputPlainTextRenderer.render(reportOutput)

    txt.lines().count().toInt should be >= 6
//    txt should include("sample report")
//    txt should include("first item")
//    txt should include("second item")
//    txt should include("third item")
//

    println("actual txt:\n"+txt)
    val expectedTxt =
      """
        |====================================================================================================
        |==                                         sample report                                          ==
        |====================================================================================================
        ||first item
        |||Ready     |Steady              |GO!!!                                                            |
        |||foo1      |bar1                |baz1                                                             |
        |||foo2      |bar2                |baz2                                                             |
        |||foo3      |bar3                |                                                                 |
        |====================================================================================================
        |""".stripMargin

    txt.trim shouldBe expectedTxt.trim
  }

}
