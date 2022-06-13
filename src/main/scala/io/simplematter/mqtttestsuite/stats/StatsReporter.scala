package io.simplematter.mqtttestsuite.stats

import io.simplematter.mqtttestsuite.config.{MqttBrokerConfig, ScenarioConfig, StatsConfig}
import io.simplematter.mqtttestsuite.model.ClientId
import io.simplematter.mqtttestsuite.stats.StatsReporter.renderReportResponse
import io.simplematter.mqtttestsuite.stats.presentation.{ReportOutputHtmlRenderer, ReportOutputModel, ReportOutputPlainTextRenderer}
import org.slf4j.LoggerFactory
import zio.{Has, RIO, Schedule, URIO, ZEnv, ZIO, clock}
import zio.clock.Clock
import zio.duration.*
import zhttp.http.*
import zhttp.http.headers.*
import zhttp.service.Server
import zio.console.Console

class StatsReporter(statsConfig: StatsConfig, statsProvider: StatsProvider) {
  import StatsReporter.log

  def run(): RIO[Clock, Unit] = {
    for {
      psF <- printStatsRegularly(statsConfig.statsInterval).fork
      _ <- statsConfig.statsPortIntOption.fold[RIO[Clock, Unit]]{
        log.debug("Stats HTTP port not specified, not exposing the stats")
        RIO.succeed(())
      }{port =>
        log.debug(s"Exposing stats on HTTP port $port")
        runHttpService(port)}
      _ <- psF.join
    } yield ()
  }


  def printStats(now: Long,
                 label: Option[String] = None) = {
    log.info("\n" + ReportOutputPlainTextRenderer.render(statsProvider.getStats().buildOutput(now, label, statsProvider.getMqttBrokerConfig(), statsProvider.getScenarioConfig())))

    if(statsConfig.printIssuesReport) {
      val issues = statsProvider.getIssuesReport()
      val issuesOutModel = issues.buildOutput(now, label)
      log.info("\n Issues report: \n" + ReportOutputPlainTextRenderer.render(issuesOutModel))
    }
  }

  private def printStatsRegularly(interval: Duration = 10.seconds): RIO[Clock, Unit] = {
    (for {
      now <- clock.instant
      _ = printStats(now.toEpochMilli(), Option(s"current - ${now.toString}"))
    } yield ()).repeat(Schedule.spaced(interval)).as(())
  }

  private def runHttpService(port: Int): RIO[Clock, Unit] = {
    Server.start[Clock](port, Http.collectZIO[Request] {
      case req@Method.GET -> !! => {
        for {
          timestamp <- clock.instant
          stats = statsProvider.getStats()
          outModel = stats.buildOutput(timestamp.toEpochMilli(), Option(timestamp.toString()), statsProvider.getMqttBrokerConfig(), statsProvider.getScenarioConfig())
        } yield renderReportResponse(req.headers.accept, outModel)
      }
      case req@Method.GET -> !! / "conclusion" => {
        ZIO {
          val stats = statsProvider.getStats()
          val scenarioConfig = statsProvider.getScenarioConfig()
          Response.text(stats.conclusion(scenarioConfig).toString)
        }
      }
      case req@Method.GET -> !! / "issues" / clientIdStr => {
        for {
          timestamp <- clock.instant
          issues = statsProvider.getIssuesReport()
          outModel = issues.buildOutputByClient(ClientId(clientIdStr), timestamp.toEpochMilli(), Option(timestamp.toString()))
        } yield renderReportResponse(req.headers.accept, outModel)
      }
      case req@Method.GET -> !! / "issues" => {
        for {
          timestamp <- clock.instant
          issues = statsProvider.getIssuesReport()
          outModel = issues.buildOutput(timestamp.toEpochMilli(), Option(timestamp.toString()))
        } yield renderReportResponse(req.headers.accept, outModel)
      }
    })
  }
}

object StatsReporter {
  private val log = LoggerFactory.getLogger(classOf[StatsReporter])

  private[stats] def preferredContentType(accept: Option[CharSequence]): String = {
    accept.toSeq.flatMap(_.toString.split(",").map(_.trim))
      .find(item => item == HeaderValues.textHtml.toString || item == HeaderValues.textPlain.toString)
      .getOrElse(HeaderValues.textPlain.toString)
  }

  private val TextHtml = HeaderValues.textHtml.toString

  private def renderReportResponse(accept: Option[CharSequence], report: ReportOutputModel): Response = {
    val contentType = preferredContentType(accept)
    contentType match {
      case TextHtml => Response(
        data = HttpData.fromString(ReportOutputHtmlRenderer.renderCompletePage(report)),
        headers = Headers(HeaderNames.contentType, HeaderValues.textHtml),
      )
      case _ =>
        Response.text(ReportOutputPlainTextRenderer.render(report))
    }
  }
}
