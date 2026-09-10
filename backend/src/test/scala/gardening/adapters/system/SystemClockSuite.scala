package gardening.adapters.system

import java.time.Instant

class SystemClockSuite extends munit.FunSuite:
  test("now returns an instant that is not in the past relative to a prior sample"):
    val before  = Instant.now()
    val sampled = SystemClock.now()
    assert(!sampled.isBefore(before))
