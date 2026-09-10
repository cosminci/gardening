package gardening.adapters.system

import java.util.UUID

class UuidIdGenSuite extends munit.FunSuite:
  test("nextId produces a parseable UUID"):
    val id     = UuidIdGen.nextId()
    val parsed = UUID.fromString(id)
    assertEquals(parsed.toString, id)
