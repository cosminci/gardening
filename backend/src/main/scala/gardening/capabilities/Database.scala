package gardening.capabilities

import language.experimental.captureChecking

/** A live, scoped database session. Capture checking (enabled for this file only) tracks
  * `DbSession^`: the session is a capability that cannot escape the `use` block that grants it.
  */
trait DbSession:
  def probe(): Boolean

/** Capture-free database-reachability port, implemented by persistence adapters. Kept plain on
  * purpose so the third-party driver boundary is never capture-checked — capture checking is
  * bounded to our own capability wrapper below, not the adapter.
  */
trait DatabaseProbe:
  def probe(): Boolean

/** Capability granting scoped access to the database. */
trait Database:
  def use[A](f: DbSession^ ?=> A): A

object Database:
  /** Builds the scoped [[Database]] capability over a capture-free [[DatabaseProbe]] adapter. */
  def make(backing: DatabaseProbe): Database =
    new Database:
      def use[A](f: DbSession^ ?=> A): A =
        val session: DbSession = new DbSession:
          def probe(): Boolean = backing.probe()
        f(using session)
