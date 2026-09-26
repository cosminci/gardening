package gardening.adapters.http

import io.circe.Codec
import sttp.shared.Identity
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.*
import sttp.tapir.server.ServerEndpoint

final case class HealthResponse(status: String, version: String) derives Codec.AsObject

object HealthApi:

  val endpoint: PublicEndpoint[Unit, Unit, HealthResponse, Any] =
    sttp.tapir.endpoint.get.in("health").out(jsonBody[HealthResponse]).summary("Liveness probe")

  def serverEndpoint(version: String): ServerEndpoint.Full[Unit, Unit, Unit, Unit, HealthResponse, Any, Identity] =
    endpoint.handleSuccess(_ => HealthResponse(status = "ok", version))
