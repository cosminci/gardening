package gardening.adapters.persistence

import gardening.domain.{JournalReadResult, PlantId}
import munit.FunSuite
import org.flywaydb.core.Flyway

import java.nio.file.Files
import java.util.UUID
import javax.sql.DataSource

import scala.util.chaining.scalaUtilChainingOps

class SqliteSeamIntegrationTest extends FunSuite:

  test("should connect to an in-memory database"):
    val connection = Sqlite.connect(SqliteLocation.InMemory(UUID.randomUUID().toString))
    try
      val _ = Flyway.configure().dataSource(connection.dataSource).load().migrate()
      seedPlant(connection.dataSource)

      SqlitePlantJournalStore.make(connection.transactor).getPlants match
        case JournalReadResult.Read(plants) => assertEquals(plants.map(_.id), Vector(PlantId("p1")))
        case other                          => fail(s"expected Read, got $other")
    finally connection.close()

  test("should connect to a file-backed database"):
    val path = Files.createTempFile("gardening-sqlite-suite", ".sqlite")
    try
      val connection = Sqlite.connect(SqliteLocation.File(path.toString))
      try
        val _ = Flyway.configure().dataSource(connection.dataSource).load().migrate()
        assertEquals(SqlitePlantJournalStore.make(connection.transactor).getPlants, JournalReadResult.Read(Vector.empty))
      finally connection.close()
    finally Files.delete(path)

  private def seedPlant(dataSource: DataSource): Unit =
    val connection = dataSource.getConnection()
    try
      val _ = connection
        .prepareStatement("insert into plant (id, species, location, status, substrate) values (?, ?, ?, ?, ?)")
        .tap: statement =>
          try
            statement.setString(1, "p1")
            statement.setString(2, "Ficus lyrata")
            statement.setString(3, "Balcony")
            statement.setString(4, "Active")
            statement.setString(5, "Perlite:100")
            val _ = statement.executeUpdate()
          finally statement.close()
    finally connection.close()
