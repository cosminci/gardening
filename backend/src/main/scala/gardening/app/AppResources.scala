package gardening.app

import com.augustnagro.magnum.Transactor
import gardening.adapters.sqlite.{Sqlite, SqliteConnection, SqliteLocation}

import javax.sql.DataSource

final class AppResources private (connection: SqliteConnection) extends AutoCloseable:

  val dataSource: DataSource = connection.dataSource
  val transactor: Transactor = connection.transactor

  override def close(): Unit = connection.close()

object AppResources:

  def acquire(database: SqliteLocation): AppResources =
    AppResources(Sqlite.make.connect(database))
