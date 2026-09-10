package gardening.adapters.persistence

import com.augustnagro.magnum.*
import gardening.capabilities.DatabaseProbe

import javax.sql.DataSource

/** Magnum-over-SQLite implementation of the capture-free [[DatabaseProbe]] port. */
final class MagnumDatabaseProbe(dataSource: DataSource) extends DatabaseProbe:
  def probe(): Boolean =
    connect(dataSource):
      sql"SELECT 1".query[Int].run().nonEmpty
