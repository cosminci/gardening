package gardening.capabilities

import java.time.Instant

trait Clock:
  def now(): Instant
