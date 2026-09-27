package gardening.adapters.system

import gardening.capabilities.Clock

class SystemClockComponentTest extends munit.FunSuite:

  private val clock: Clock = SystemClock

  test("should sample instants in non-decreasing order"):
    val first  = clock.now()
    val second = clock.now()
    assert(second.compareTo(first) >= 0, "a later sample must not predate an earlier one")
