package gardening.adapters.sqlite

import munit.FunSuite

import java.nio.file.Files
import java.util.UUID
import scala.util.{Try, Using}

extension [A](result: Try[A])
  // A failure here is a test assertion failing inside a Using.Manager block; letting it surface
  // as the test's own exception is exactly the desired behavior.
  @SuppressWarnings(Array("org.wartremover.warts.TryPartial"))
  private[sqlite] def orFail: A = result.get

class SqliteSeamIntegrationTest extends FunSuite:

  test("should retain an in-memory database while its resource is open"):
    val sqlite = buildSqlite

    Using.Manager { use =>
      val connection = use(sqlite.connect(SqliteLocation.InMemory(UUID.randomUUID().toString)))

      val writeStatement = use(use(connection.dataSource.getConnection()).createStatement())
      val _              = writeStatement.executeUpdate("create table note (value text not null)")
      val _              = writeStatement.executeUpdate("insert into note (value) values ('watered')")

      val readStatement = use(use(connection.dataSource.getConnection()).createStatement())
      val result        = use(readStatement.executeQuery("select value from note"))

      assert(result.next())
      assertEquals(result.getString("value"), "watered")
    }.orFail

  test("should retain a file-backed database after its resource is reopened"):
    val databasePath = Files.createTempFile("gardening-sqlite-suite", ".sqlite")
    val sqlite       = buildSqlite
    try
      Using.Manager { use =>
        val writeConnection = use(sqlite.connect(SqliteLocation.File(databasePath.toString)))
        val writeStatement  = use(use(writeConnection.dataSource.getConnection()).createStatement())
        val _               = writeStatement.executeUpdate("create table note (value text not null)")
        val _               = writeStatement.executeUpdate("insert into note (value) values ('repotted')")

        val readConnection = use(sqlite.connect(SqliteLocation.File(databasePath.toString)))
        val readStatement  = use(use(readConnection.dataSource.getConnection()).createStatement())
        val result         = use(readStatement.executeQuery("select value from note"))

        assert(result.next())
        assertEquals(result.getString("value"), "repotted")
      }.orFail
    finally Files.delete(databasePath)

  private def buildSqlite = Sqlite.make
