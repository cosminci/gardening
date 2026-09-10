package gardening.adapters.system

import gardening.capabilities.Clock

import java.time.Instant

/** Live [[Clock]] backed by the system UTC clock. */
object SystemClock extends Clock:
  def now(): Instant = Instant.now()
