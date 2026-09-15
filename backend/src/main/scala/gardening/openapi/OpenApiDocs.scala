package gardening.openapi

import gardening.adapters.http.Endpoints
import sttp.apispec.openapi.circe.yaml.*
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter

object OpenApiDocs:
  val yaml: String =
    OpenAPIDocsInterpreter()
      .toOpenAPI(Endpoints.all, "Gardening API", "0.1.0")
      .toYaml
