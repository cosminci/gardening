package gardening.adapters.persistence

import io.circe.Json

class StoredSubstrateUnitTest extends munit.FunSuite:

  test("should reject a substrate that is not an array"):
    assert(StoredSubstrate.decodeJson(Json.obj()).isInvalid)

  test("should reject a substrate component with an invalid identifier"):
    val substrate = Json.arr(
      Json.obj(
        "component" -> Json.fromString("not-a-uuid"),
        "share"     -> Json.fromInt(100)
      )
    )

    StoredSubstrate.decodeJson(substrate).fold(
      errors => assertEquals(errors.head.getMessage, "invalid stored substrate: invalid component id: not-a-uuid"),
      _ => fail("expected invalid substrate")
    )
