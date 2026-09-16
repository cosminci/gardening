package gardening.domain

import java.time.Instant

trait Clock:
  def now(): Instant
