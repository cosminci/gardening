package gardening.adapters.persistence

import com.augustnagro.magnum.Transactor
import org.sqlite.SQLiteDataSource

import java.sql.Connection
import javax.sql.DataSource
import scala.util.chaining.scalaUtilChainingOps

enum SqliteLocation:
  case InMemory(name: String)
  case File(path: String)

final class SqliteConnection(
    val dataSource: DataSource,
    val transactor: Transactor,
    maybeKeepAlive: Option[Connection]
) extends AutoCloseable:

  override def close(): Unit =
    maybeKeepAlive.foreach(_.close())

object Sqlite:

  def connect(location: SqliteLocation): SqliteConnection =
    val dataSource = new SQLiteDataSource().tap(_.setUrl(urlFor(location)))
    val transactor = Transactor(dataSource, connectionConfig = enableForeignKeys)
    SqliteConnection(dataSource, transactor, maybeKeepAlive = Some(dataSource.getConnection()))

  private def urlFor(location: SqliteLocation): String =
    location match
      case SqliteLocation.InMemory(name) => s"jdbc:sqlite:file:$name?mode=memory&cache=shared"
      case SqliteLocation.File(path)     => s"jdbc:sqlite:$path"

  private def enableForeignKeys(connection: Connection): Unit =
    val statement = connection.createStatement()
    val _         = statement.execute("PRAGMA foreign_keys = ON")
    statement.close()
