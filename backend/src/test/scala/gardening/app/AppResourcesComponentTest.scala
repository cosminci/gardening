package gardening.app

import gardening.adapters.persistence.SqliteLocation

import java.sql.SQLException
import java.util.UUID
import scala.util.Using

class AppResourcesComponentTest extends munit.FunSuite:

  test("should release the SQLite database lifetime when closed"):
    val resources = AppResources.acquire(SqliteLocation.InMemory(UUID.randomUUID().toString))
    Using.resource(resources.dataSource.getConnection): connection =>
      Using.resource(connection.createStatement()): statement =>
        val _ = statement.execute("CREATE TABLE resource_lifetime (id INTEGER)")

    resources.close()

    intercept[SQLException]:
      Using.resource(resources.dataSource.getConnection): connection =>
        Using.resource(connection.createStatement()): statement =>
          val _ = statement.executeQuery("SELECT id FROM resource_lifetime")
