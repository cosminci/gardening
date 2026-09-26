package gardening.adapters.system

import gardening.capabilities.Clock

import java.time.Instant

object SystemClock extends Clock:

  def now(): Instant = Instant.now()
