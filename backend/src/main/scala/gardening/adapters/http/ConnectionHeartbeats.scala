package gardening.adapters.http

import gardening.capabilities.{Clock, LiveConnectionsGauge}
import ox.discard

import java.time.{Duration as JavaDuration, Instant}
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.FiniteDuration

trait ConnectionHeartbeats extends LiveConnectionsGauge:
  def touch(connection: UUID): Unit

object ConnectionHeartbeats:

  def make(staleness: FiniteDuration)(using clock: Clock): ConnectionHeartbeats = LiveConnectionHeartbeats(staleness)

  private class LiveConnectionHeartbeats(staleness: FiniteDuration)(using clock: Clock) extends ConnectionHeartbeats:
    private val lastSeen   = AtomicReference(Map.empty[UUID, Instant])
    private val staleAfter = JavaDuration.ofNanos(staleness.toNanos)

    override def touch(connection: UUID): Unit = lastSeen.updateAndGet(_.updated(connection, clock.now())).discard

    override def liveCount: Int =
      lastSeen.updateAndGet(seen => seen.filter((_, at) => JavaDuration.between(at, clock.now()).compareTo(staleAfter) <= 0)).size
