package gardening.adapters.http

import io.circe.Codec
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.*

final case class HealthResponse(status: String, version: String) derives Codec.AsObject

object Endpoints:
  val health: PublicEndpoint[Unit, Unit, HealthResponse, Any] =
    endpoint.get
      .in("health")
      .out(jsonBody[HealthResponse])
      .summary("Liveness probe")

  val all: List[AnyEndpoint] = List(health)
