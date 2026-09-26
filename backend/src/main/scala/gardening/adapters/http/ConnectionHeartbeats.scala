package gardening.adapters.http

import gardening.domain.Clock
import ox.discard

import java.time.{Duration as JavaDuration, Instant}
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.FiniteDuration

/**
 * Tracks how many long-lived connections (e.g. an open WebSocket) are currently live by their own periodic heartbeat, not by a paired open/close
 * event: a connection that stops touching its heartbeat simply ages out of `liveCount` within `staleness`, so a single missed close signal can't leak
 * the count forever the way an incremented-then-decremented counter would.
 */
trait ConnectionHeartbeats:
  def touch(connection: UUID): Unit
  def liveCount: Int

object ConnectionHeartbeats:

  def make(staleness: FiniteDuration)(using clock: Clock): ConnectionHeartbeats = LiveConnectionHeartbeats(staleness)

  private class LiveConnectionHeartbeats(staleness: FiniteDuration)(using clock: Clock) extends ConnectionHeartbeats:
    private val lastSeen   = AtomicReference(Map.empty[UUID, Instant])
    private val staleAfter = JavaDuration.ofNanos(staleness.toNanos)

    override def touch(connection: UUID): Unit = lastSeen.updateAndGet(_.updated(connection, clock.now())).discard

    override def liveCount: Int =
      lastSeen.updateAndGet(seen => seen.filter((_, at) => JavaDuration.between(at, clock.now()).compareTo(staleAfter) <= 0)).size
