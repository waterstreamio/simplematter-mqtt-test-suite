package io.simplematter.mqtttestsuite.stats.presentation

import io.simplematter.mqtttestsuite.model.ClientId

object ReportOutputHtmlRenderer {

  private val statsReportLink = "/"
  private val issuesReportLink = "/issues"
  private def clientIssuesReportLink(clientId: ClientId) = s"/issues/${clientId.value}"

  def render(reportOutput: ReportOutputModel): String = {
    s"""
      |<div class="report">
      |<h1>${reportOutput.title}</h1>
      |<ul>
      |${renderItems(reportOutput.items)}
      |</ul>
      |</div>
      |""".stripMargin
  }

  private def renderItems(items: Seq[ReportItem]): String = {
    items.map { item =>
      "<li>" + renderItem(item) + "</li>"
    }.mkString("\n")
  }

  private def renderItem(item: ReportItem): String = {
    item match {
      case PlainText(text) => text
      case EmphasizedText(text) => s"<strong>$text</strong>"
      case LinkText(text, target) => s"<a href=\"${toUrl(target)}\">$text</a>"
      case CompositeText(elements) => elements.map(renderItem).mkString("")
      case table: Table => renderTable(table)
    }
  }


  private def renderTable(table: Table): String = {
    val defaultColumnWidth = (100 / table.head.count(_.width.isEmpty)) + "%"
    (Seq("<table border=\"1\" style=\"width: 100%\"><tr>") ++
      table.head.map {
        case ColumnHead(caption, widthOpt) => s"<th scope=\"col\" style=\"width: ${widthOpt.fold(defaultColumnWidth)(_ + "em")}\">$caption</th>"
//        case ColumnHead(caption, Some(width)) => s"<th scope=\"col\" style=\"width: ${width}em\">$caption</th>"
//        case ColumnHead(caption, None) => s"<th scope=\"col\" width: ${defaultColumnWidth} >$caption</th>"
      } ++
      Seq("</tr>") ++
      table.body.map { row =>
        "<tr>" + row.map(item => "<td>" + renderItem(item) + "</td>").mkString("") + "</tr>"
      } ++
      Seq("</table>")).mkString("\n")
  }

  private def toUrl(linkTarget: LinkTarget): String =
    linkTarget match {
      case LinkToStatsSummary => statsReportLink
      case LinkToIssuesReport => issuesReportLink
      case LinkToClientIssues(clientId) => clientIssuesReportLink(clientId)
    }

  def renderCompletePage(reportOutput: ReportOutputModel): String = {
    s"""
      |<html>
      |<head>
      |<title>${reportOutput.title}</title>
      |</head>
      |<body>
      |[<a href="${statsReportLink}">stats</a> | <a href="${issuesReportLink}">issues</a>]
      |${render(reportOutput)}
      |</body>
      |</html>
      |""".stripMargin

  }
}
