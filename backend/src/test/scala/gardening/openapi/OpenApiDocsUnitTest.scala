package gardening.openapi

import gardening.adapters.http.OpenApiDocs

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
