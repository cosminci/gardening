package gardening.adapters.sqlite

import munit.FunSuite

import java.nio.file.Files
import java.util.UUID
import scala.util.Using

class SqliteSeamIntegrationTest extends FunSuite:

  test("should retain an in-memory database while its resource is open"):
    val sqlite = buildSqlite

    Using.resource(sqlite.connect(SqliteLocation.InMemory(UUID.randomUUID().toString))): connection =>
      Using.resource(connection.dataSource.getConnection()): writer =>
        Using.resource(writer.createStatement()): statement =>
          val _ = statement.executeUpdate("create table note (value text not null)")
          val _ = statement.executeUpdate("insert into note (value) values ('watered')")

      val actualNote =
        Using.resource(connection.dataSource.getConnection()): reader =>
          Using.resource(reader.createStatement()): statement =>
            Using.resource(statement.executeQuery("select value from note")): result =>
              assert(result.next())
              result.getString("value")

      assertEquals(actualNote, "watered")

  test("should retain a file-backed database after its resource is reopened"):
    val databasePath = Files.createTempFile("gardening-sqlite-suite", ".sqlite")
    val sqlite       = buildSqlite
    try
      Using.resource(sqlite.connect(SqliteLocation.File(databasePath.toString))): connection =>
        Using.resource(connection.dataSource.getConnection()): writer =>
          Using.resource(writer.createStatement()): statement =>
            val _ = statement.executeUpdate("create table note (value text not null)")
            val _ = statement.executeUpdate("insert into note (value) values ('repotted')")

      val actualNote =
        Using.resource(sqlite.connect(SqliteLocation.File(databasePath.toString))): connection =>
          Using.resource(connection.dataSource.getConnection()): reader =>
            Using.resource(reader.createStatement()): statement =>
              Using.resource(statement.executeQuery("select value from note")): result =>
                assert(result.next())
                result.getString("value")

      assertEquals(actualNote, "repotted")
    finally Files.delete(databasePath)

  private def buildSqlite = Sqlite.make
