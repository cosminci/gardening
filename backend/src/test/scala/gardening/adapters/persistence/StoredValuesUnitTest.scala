package gardening.adapters.persistence

import gardening.domain.TestNomenclatureIds
import io.circe.Json

class StoredValuesUnitTest extends munit.FunSuite:

  test("should reject a substrate that is not an array"):
    val payload = """{"substrate":{},"note":null}"""

    assert(decodeSubstrate("{}").isLeft)
    assert(payload.decodeStoredOperation("Repot").isLeft)

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

  test("should reject an invalid substrate share"):
    val substrate =
      s"""[{"component":"${TestNomenclatureIds.Perlite.value}","share":0}]"""

    assertEquals(decodeSubstrate(substrate).left.map(_.getMessage), Left("invalid stored substrate: invalid share: 0"))

  test("should reject an invalid stored pesticide identifier"):
    val payload = """{"actions":[],"pesticides":["not-a-uuid"],"moisture":"Wet","note":null}"""

    payload.decodeStoredOperation("Care").fold(
      error => assertEquals(error.getMessage, "invalid stored operation payload: invalid pesticide id: not-a-uuid"),
      _ => fail("expected invalid operation")
    )

  test("should fail fast when a stored operation cannot be decoded"):
    val invalid = List(
      "not-json"                                                                 -> "Care",
      """{"pesticides":[],"moisture":"Wet","note":null}"""                       -> "Care",
      """{"actions":["Unknown"],"pesticides":[],"moisture":"Wet","note":null}""" -> "Care",
      """{"actions":[],"pesticides":[],"moisture":"Unknown","note":null}"""      -> "Care",
      """{"note":null}"""                                                        -> "Repot"
    )

    invalid.foreach: (payload, kind) =>
      assert(payload.decodeStoredOperation(kind).isLeft)
