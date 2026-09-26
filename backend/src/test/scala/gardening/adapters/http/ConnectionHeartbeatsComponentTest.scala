package gardening.adapters.http

import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*

class ConnectionHeartbeatsComponentTest extends munit.FunSuite:

  private val start = Instant.parse("2026-01-01T00:00:00Z")

  test("should keep counting a connection as long as it keeps touching within the staleness window"):
    val now        = AtomicReference(start)
    val heartbeats = buildHeartbeats(now)
    val connection = UUID.randomUUID()

    heartbeats.touch(connection)
    now.set(start.plusSeconds(4))
    heartbeats.touch(connection)

    assertEquals(heartbeats.liveCount, 1)

  test("should stop counting a connection once it goes silent past the staleness window"):
    val now        = AtomicReference(start)
    val heartbeats = buildHeartbeats(now)
    val connection = UUID.randomUUID()

    heartbeats.touch(connection)
    now.set(start.plusSeconds(10))

    assertEquals(heartbeats.liveCount, 0)

  test("should count every independently touched connection"):
    val now        = AtomicReference(start)
    val heartbeats = buildHeartbeats(now)

    heartbeats.touch(UUID.randomUUID())
    heartbeats.touch(UUID.randomUUID())

    assertEquals(heartbeats.liveCount, 2)

  private def buildHeartbeats(now: AtomicReference[Instant]) = ConnectionHeartbeats.make(staleness = 5.seconds)(using () => now.get())
