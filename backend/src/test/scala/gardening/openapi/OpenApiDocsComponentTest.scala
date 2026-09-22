package gardening.openapi

import cats.syntax.either._
import gardening.adapters.http.OpenApiDocs
import io.circe.yaml.parser.parse

class OpenApiDocsComponentTest extends munit.FunSuite:
  test("yaml documents the health and care-journal contract"):
    val yaml = OpenApiDocs.yaml

    assert(yaml.contains("/health"))
    assert(yaml.contains("/plants:"))
    assert(yaml.contains("/plants/{plantId}/operations:"))
    assert(yaml.contains("/operations/{operationId}:"))
    assert(yaml.contains("kind:"))
    assert(yaml.contains("care"))
    assert(yaml.contains("repot"))
    assert(yaml.contains("enum:"))
    assert(yaml.contains("- watered"))
    assert(yaml.contains("- moderatePlus"))
    assert(yaml.contains("- archived"))
    assert(yaml.contains("/substrate-components:"))
    assert(yaml.contains("/substrate-components/{componentId}:"))
    assert(yaml.contains("/pesticides:"))
    assert(yaml.contains("/pesticides/{pesticideId}:"))
    assert(yaml.contains("format: uuid"))
    assert(yaml.contains("minimum: 1"))
    assert(yaml.contains("maximum: 100"))
    assert(yaml.contains("- 'null'"))
    assert(yaml.contains("Gardening API"))

  test("operation editing documents its distinct error responses"):
    val document = parse(OpenApiDocs.yaml).fold(error => fail(error.message), identity)
    val paths    = document.hcursor.downField("paths")

    assertEquals(responseCodes(paths, "/plants", "get"), Set("200", "default"))
    assertEquals(responseCodes(paths, "/plants/{plantId}/operations", "get"), Set("200", "default"))
    assertEquals(responseCodes(paths, "/plants/{plantId}/operations", "post"), Set("201", "400", "default"))
    assertEquals(responseCodes(paths, "/operations/{operationId}", "put"), Set("200", "400", "404", "409", "500"))
    assertEquals(responseCodes(paths, "/substrate-components", "get"), Set("200", "default"))
    assertEquals(responseCodes(paths, "/substrate-components", "post"), Set("201", "400", "default"))
    assertEquals(responseCodes(paths, "/substrate-components/{componentId}", "put"), Set("200", "400", "404", "500"))
    assertEquals(responseCodes(paths, "/pesticides", "get"), Set("200", "default"))
    assertEquals(responseCodes(paths, "/pesticides", "post"), Set("201", "400", "default"))
    assertEquals(responseCodes(paths, "/pesticides/{pesticideId}", "put"), Set("200", "400", "404", "500"))
    val serverErrors = paths.downField("/operations/{operationId}").downField("put").downField("responses").downField("500")
    val errorSchema  = serverErrors.downField("content").downField("application/json").downField("schema").downField("$ref").as[String]
    assertEquals(errorSchema, "#/components/schemas/ApiError".asRight)

  private def responseCodes(paths: io.circe.ACursor, path: String, method: String): Set[String] =
    paths.downField(path).downField(method).downField("responses").keys.fold(fail(s"$method $path responses missing"))(_.toSet)
