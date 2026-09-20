package gardening.adapters.http

import sttp.apispec.openapi.circe.yaml.*
import sttp.tapir.AnyEndpoint
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter

object OpenApiDocs:

  private val endpoints: List[AnyEndpoint] = List(HealthApi.endpoint)

  val yaml: String =
    OpenAPIDocsInterpreter()
      .toOpenAPI(endpoints, title = "Gardening API", version = "0.1.0")
      .toYaml
