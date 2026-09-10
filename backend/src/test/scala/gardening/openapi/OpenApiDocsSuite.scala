package gardening.openapi

class OpenApiDocsSuite extends munit.FunSuite:
  test("yaml documents the health endpoint and API title"):
    val yaml = OpenApiDocs.yaml
    assert(yaml.contains("/health"))
    assert(yaml.contains("Gardening API"))
