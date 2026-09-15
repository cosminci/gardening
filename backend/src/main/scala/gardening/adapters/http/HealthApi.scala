package gardening.adapters.http

import sttp.tapir.server.ServerEndpoint

object HealthApi:
  def serverEndpoint(version: String): ServerEndpoint.Full[Unit, Unit, Unit, Unit, HealthResponse, Any, Identity] =
    Endpoints.health.handleSuccess(_ => HealthResponse("ok", version))
