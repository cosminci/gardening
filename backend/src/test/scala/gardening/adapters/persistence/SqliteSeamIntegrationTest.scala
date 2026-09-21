package gardening.adapters.persistence

import gardening.domain.{GetPlantsResult, PlantId}
import munit.FunSuite
import org.flywaydb.core.Flyway

import java.nio.file.Files
import java.util.UUID
import javax.sql.DataSource

class SqliteSeamIntegrationTest extends FunSuite:

  test("should connect to an in-memory database"):
    val connection = Sqlite.connect(SqliteLocation.InMemory(UUID.randomUUID().toString))
    try
      val _ = Flyway.configure().dataSource(connection.dataSource).load().migrate()
      seedPlant(connection.dataSource)

      SqlitePlantJournalStore.make(connection.transactor).getPlants match
        case GetPlantsResult.Read(plants) => assertEquals(plants.map(_.id), Vector(PlantId("p1")))
        case other                        => fail(s"expected Read, got $other")
    finally connection.close()

  test("should retain file-backed data after closing and reopening the database"):
    val path = Files.createTempFile("gardening-sqlite-suite", ".sqlite")
    try
      val firstConnection = Sqlite.connect(SqliteLocation.File(path.toString))
      try
        val _ = Flyway.configure().dataSource(firstConnection.dataSource).load().migrate()
        seedPlant(firstConnection.dataSource)
      finally firstConnection.close()

      val reopenedConnection = Sqlite.connect(SqliteLocation.File(path.toString))
      try
        SqlitePlantJournalStore.make(reopenedConnection.transactor).getPlants match
          case GetPlantsResult.Read(plants) => assertEquals(plants.map(_.id), Vector(PlantId("p1")))
          case other                        => fail(s"expected Read, got $other")
      finally reopenedConnection.close()
    finally Files.delete(path)

  private def seedPlant(dataSource: DataSource): Unit =
    val connection = dataSource.getConnection()
    try
      val statement =
        connection.prepareStatement("insert into plant (id, species, location, status, substrate) values (?, ?, ?, ?, ?)")
      try
        statement.setString(1, "p1")
        statement.setString(2, "Ficus lyrata")
        statement.setString(3, "Balcony")
        statement.setString(4, "Active")
        statement.setString(5, """[{"component":"00000000-0000-4000-8000-000000000003","share":100}]""")
        val _ = statement.executeUpdate()
      finally statement.close()
    finally connection.close()
