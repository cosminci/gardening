package gardening.adapters.http

import gardening.application.HealthService
import gardening.capabilities.{Clock, Database}
import sttp.tapir.server.ServerEndpoint

/** Binds the health endpoint to its direct-style server logic. */
object HealthApi:
  def serverEndpoint(version: String)(using Database, Clock): ServerEndpoint[Any, Identity] =
    Endpoints.health.handleSuccess { _ =>
      val report = HealthService.check(version)
      HealthResponse(report.status.toString, report.version, report.database, report.checkedAt.toString)
    }
