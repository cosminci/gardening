package gardening.adapters.http

import io.circe.Codec
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.*

/** Wire representation of a health report. */
final case class HealthResponse(status: String, version: String, database: Boolean, checkedAt: String)
    derives Codec.AsObject

/**
 * Single source of truth for the public HTTP contract. Consumed both by the server interpreter and by the OpenAPI
 * generator, so the served API and the published contract can never diverge.
 */
object Endpoints:
  val health: PublicEndpoint[Unit, Unit, HealthResponse, Any] =
    endpoint.get
      .in("health")
      .out(jsonBody[HealthResponse])
      .summary("Liveness and readiness probe")
      .description("Reports the service version and database reachability.")

  val all: List[AnyEndpoint] = List(health)
