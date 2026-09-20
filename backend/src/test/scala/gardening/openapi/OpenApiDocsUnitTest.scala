package gardening.openapi

import gardening.adapters.http.OpenApiDocs

class OpenApiDocsUnitTest extends munit.FunSuite:
  test("yaml documents the health endpoint and API title"):
    val yaml = OpenApiDocs.yaml
    assert(yaml.contains("/health"))
    assert(yaml.contains("Gardening API"))
