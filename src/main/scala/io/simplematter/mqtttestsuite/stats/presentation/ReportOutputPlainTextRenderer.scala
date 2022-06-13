package io.simplematter.mqtttestsuite.stats.presentation

object ReportOutputPlainTextRenderer {
  def render(reportOutput: ReportOutputModel, width: Int = 100): String = {

    val hbar = "=".repeat(width)
    val spaces = width - 4 - reportOutput.title.length
    val headText = "==" + " ".repeat(Math.floorDiv(spaces, 2)) + reportOutput.title + " ".repeat(Math.ceil(spaces.toDouble/2).toInt) + "=="

    (Seq(
      hbar,
      headText,
      hbar,
    ) ++ reportOutput.items.map {
      case textItem: Text => "|" + textItem.text
      case table: Table => renderTable(table, width - 1).map("|" + _).mkString("\n")
    } ++
      Seq(hbar)).mkString("\n")
  }

  private def renderTable(table: Table, width: Int): Seq[String] = {
    val definedColWidth = table.head.flatMap(_.width)
    val remaininigSpace = width - 1 - table.head.size - definedColWidth.sum
    val defaultColWidth = remaininigSpace / table.head.count(_.width.isEmpty)
    val allColWidth = table.head.map(ch => ch.width.getOrElse(defaultColWidth))

    buildTableLine(allColWidth, table.head.map(_.caption)) +:
    table.body.map { lineItems =>
      buildTableLine(allColWidth, lineItems.map(_.text).padTo(table.head.size, ""))
    }
  }

  private def buildTableLine(colWidth: Seq[Int], items: Iterable[String]): String = {
    "|" + (colWidth.zip(items).map { (width, content) =>
      content + " ".repeat(Math.max(0, width - content.length))
    }).mkString("|") + "|"
  }

//  private def formatCol(content: String, width: Int): String = {
//    content + " ".repeat(Math.max(0, width - content.length))
//  }

}
