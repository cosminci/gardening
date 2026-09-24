package gardening.domain.journal

import java.time.Instant
import munit.FunSuite

class OperationDateRangeComponentTest extends FunSuite:

  test("should reject a recorded range with its last operation before its first"):
    val first = Instant.parse("2026-04-03T18:00:00Z")
    val last  = Instant.parse("2026-02-01T10:00:00Z")

    val error = intercept[IllegalArgumentException](OperationDateRange.Recorded(first, last))

    assertEquals(error.getMessage, "requirement failed: last recorded operation cannot precede first")
