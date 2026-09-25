package gardening.adapters.http

import sttp.apispec.openapi.circe.yaml.*
import sttp.tapir.docs.openapi.{OpenAPIDocsInterpreter, OpenAPIDocsOptions}

object OpenApiDocs:

  private val endpoints =
    HealthApi.endpoint :: PlantApi.publicEndpoints ++ AttentionApi.publicEndpoints ++
      OperationApi.publicEndpoints ++ PesticideApi.publicEndpoints ++ SubstrateApi.publicEndpoints
  private val options = OpenAPIDocsOptions.default.copy(markOptionsAsNullable = true)

  val yaml: String = OpenAPIDocsInterpreter(options).toOpenAPI(endpoints, title = "Gardening API", version = "0.1.0").toYaml
