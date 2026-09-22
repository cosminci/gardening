package gardening.adapters.system

import gardening.domain.IdGenerator

class UuidIdGeneratorComponentTest extends munit.FunSuite:

  private val idGenerator: IdGenerator = UuidIdGenerator

  test("should produce a distinct id on each call"):
    assertNotEquals(idGenerator.nextId(), idGenerator.nextId())
