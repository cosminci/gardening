package gardening.openapi

import gardening.adapters.http.Endpoints
import sttp.apispec.openapi.circe.yaml.*
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter

/**
 * OpenAPI document derived from the tapir endpoints. This is the single upstream source for the committed contract; the
 * generated TypeScript client is produced from it.
 */
object OpenApiDocs:
  val yaml: String =
    OpenAPIDocsInterpreter()
      .toOpenAPI(Endpoints.all, "Gardening API", "0.1.0")
      .toYaml
