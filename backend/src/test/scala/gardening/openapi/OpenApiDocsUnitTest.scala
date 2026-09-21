package gardening.openapi

import gardening.adapters.http.OpenApiDocs
import io.circe.yaml.parser.parse

class OpenApiDocsUnitTest extends munit.FunSuite:
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
    assert(yaml.contains("- kekkilaUniversal"))
    assert(yaml.contains("- archived"))
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
    assertEquals(
      responseCodes(paths, "/operations/{operationId}", "put"),
      Set("200", "400", "404", "409", "500")
    )
    assertEquals(
      paths
        .downField("/operations/{operationId}")
        .downField("put")
        .downField("responses")
        .downField("500")
        .downField("content")
        .downField("application/json")
        .downField("schema")
        .downField("$ref")
        .as[String],
      Right("#/components/schemas/ApiError")
    )

  private def responseCodes(paths: io.circe.ACursor, path: String, method: String): Set[String] =
    paths.downField(path).downField(method).downField("responses").keys.fold(fail(s"$method $path responses missing"))(_.toSet)
