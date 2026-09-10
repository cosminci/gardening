package gardening.capabilities

import java.time.Instant

/**
 * Capability providing the current instant. Injected via `using` so domain code stays deterministic under test.
 */
trait Clock:
  def now(): Instant
