package io.simplematter.mqtttestsuite.stats

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class StatsReporterSpec extends AnyFlatSpec
  with should.Matchers
  with ScalaCheckPropertyChecks
  with TableDrivenPropertyChecks {

  "StatsReporter" should "detect preferred content type" in {
    forAll(Table(
      ("Accept", "Expected preferrec Content-Type"),
      ("text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9", "text/html"),
      ("text/html", "text/html"),
      ("text/html,text/plain", "text/html"),
      ("text/plain,text/html", "text/plain"),
      ("text/plain", "text/plain"),
      ("*/*", "text/plain"),
      ("", "text/plain")
    )) {(accept: String, expectedContentType: String) =>
      StatsReporter.preferredContentType(Some(accept)) shouldBe expectedContentType
    }

    withClue("Default content type") {
      StatsReporter.preferredContentType(None) shouldBe "text/plain"
    }
  }
}
