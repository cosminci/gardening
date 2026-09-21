package gardening.adapters.persistence

import io.circe.Json

class StoredOperationPayloadUnitTest extends munit.FunSuite:

  test("should reject a substrate that is not an array"):
    val payload = """{"substrate":{},"note":null}"""

    assert(payload.decode("Repot").isInvalid)

  test("should reject a substrate component with an invalid identifier"):
    val substrate = Json.arr(
      Json.obj(
        "component" -> Json.fromString("not-a-uuid"),
        "share"     -> Json.fromInt(100)
      )
    )

    decodeSubstrate(substrate.noSpaces).fold(
      error => assertEquals(error.getMessage, "invalid stored substrate: invalid component id: not-a-uuid"),
      _ => fail("expected invalid substrate")
    )

  test("should reject an invalid stored pesticide identifier"):
    val payload = """{"actions":[],"pesticides":["not-a-uuid"],"moisture":"Wet","note":null}"""

    payload.decode("Care").fold(
      errors => assertEquals(errors.head.getMessage, "invalid stored operation payload: invalid pesticide id: not-a-uuid"),
      _ => fail("expected invalid operation")
    )
