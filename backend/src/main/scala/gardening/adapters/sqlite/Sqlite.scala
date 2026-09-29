package gardening.adapters.sqlite

import cats.syntax.option.*
import com.augustnagro.magnum.Transactor
import org.sqlite.SQLiteDataSource

import java.sql.Connection
import javax.sql.DataSource
import scala.concurrent.duration.FiniteDuration
import scala.util.chaining.scalaUtilChainingOps

enum SqliteLocation:
  case InMemory(name: String)
  case File(path: String)

final class SqliteConnection(
    val dataSource: DataSource,
    val transactor: Transactor,
    maybeKeepAlive: Option[Connection]
) extends AutoCloseable:

  override def close(): Unit = maybeKeepAlive.foreach(_.close())

trait Sqlite:
  def connect(location: SqliteLocation): SqliteConnection

object Sqlite:

  def make(storageLockTimeout: FiniteDuration): Sqlite = LiveSqlite(storageLockTimeout)

  final private class LiveSqlite(storageLockTimeout: FiniteDuration) extends Sqlite:
    override def connect(location: SqliteLocation): SqliteConnection =
      val dataSource     = new SQLiteDataSource().tap(_.setUrl(urlFor(location)))
      val configure      = configureConnection(storageLockTimeout)
      val transactor     = Transactor(dataSource, connectionConfig = configure)
      val maybeKeepAlive = location match
        case _: SqliteLocation.InMemory => dataSource.getConnection().tap(configure).some
        case _: SqliteLocation.File     => none
      SqliteConnection(dataSource, transactor, maybeKeepAlive)

  private def urlFor(location: SqliteLocation): String =
    location match
      case SqliteLocation.InMemory(name) => s"jdbc:sqlite:file:$name?mode=memory&cache=shared"
      case SqliteLocation.File(path)     => s"jdbc:sqlite:$path"

  private def configureConnection(storageLockTimeout: FiniteDuration)(connection: Connection): Unit =
    val statement = connection.createStatement()
    try
      val _ = statement.execute("PRAGMA foreign_keys = ON")
      val _ = statement.execute(s"PRAGMA busy_timeout = ${storageLockTimeout.toMillis}")
    finally statement.close()
