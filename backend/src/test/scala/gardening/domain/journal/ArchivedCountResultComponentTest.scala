package gardening.domain.journal

import munit.FunSuite

class ArchivedCountResultComponentTest extends FunSuite:

  test("should reject a negative archived plant count"):
    val invalidCount = -1L

    val error = intercept[IllegalArgumentException](ArchivedCountResult.Counted(invalidCount))

    assertEquals(error.getMessage, "requirement failed: archived plant count must be non-negative")
