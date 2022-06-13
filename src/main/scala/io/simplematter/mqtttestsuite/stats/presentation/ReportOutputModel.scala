package io.simplematter.mqtttestsuite.stats.presentation

import io.simplematter.mqtttestsuite.model.ClientId

case class ReportOutputModel(title: String, items: Seq[ReportItem]) {
}

sealed trait ReportItem

sealed trait Text extends ReportItem {
  def text: String
}

case class PlainText(text: String) extends Text

object PlainText {
  val space = PlainText(" ")
  val commaSpace = PlainText(", ")
  val openParentheses = PlainText("(")
  val closeParentheses = PlainText(")")
}

case class EmphasizedText(text: String) extends Text

case class LinkText(text: String, target: LinkTarget) extends Text

object LinkText {
  def toClientIssues(clientId: ClientId) = LinkText(clientId.value, LinkToClientIssues(clientId))
}

case class CompositeText(elements: Seq[Text]) extends Text {
  override lazy val text = elements.map(_.text).mkString("")
}

sealed trait LinkTarget

case object LinkToStatsSummary extends LinkTarget

case object LinkToIssuesReport extends LinkTarget

case class LinkToClientIssues(clientId: ClientId) extends LinkTarget

case class Table(head: Seq[ColumnHead], body: Seq[Seq[Text]]) extends ReportItem

case class ColumnHead(caption: String, width: Option[Int] = None)

