package gardening.adapters.persistence

import cats.syntax.option.*
import com.augustnagro.magnum.Transactor
import gardening.domain.*
import gardening.domain.catalog.*
import gardening.domain.pesticide.{GetPesticideResult, PesticideStore, UpdatePesticideResult}
import org.flywaydb.core.Flyway

import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource
import scala.util.Using

class SqlitePesticideStoreSeamIntegrationTest extends munit.FunSuite:

  private val pesticideId   = PesticideId(UUID.fromString("10000000-0000-4000-8000-000000000002"))
  private val pesticideData = PesticideData(PesticideName("Sulfur"), PesticideType.Fungicide, none[PesticideInfo])
  private val pesticide     = Pesticide(pesticideId, pesticideData, PesticideStatus.Active)

  test("should read seeded pesticides and persist additions, edits, and archiving"):
    Using.resource(storeResource): resource =>
      val store   = resource.store
      val missing = store.getPesticide(pesticideId)
      val seeded  = store.getPesticides

      val added          = store.addPesticide(pesticide)
      val afterAdd       = store.getPesticide(pesticideId)
      val editedData     = PesticideData(PesticideName("Wettable sulfur"), PesticideType.Treatment, PesticideInfo("2g/L").some)
      val edited         = pesticide.copy(data = editedData)
      val editResult     = store.updatePesticide(edited)
      val archived       = edited.copy(status = PesticideStatus.Archived)
      val archiveResult  = store.updatePesticide(archived)
      val afterArchive   = store.getPesticide(pesticideId)
      val expectedSeeded = Vector(
        ("ORTIVA TOP", PesticideType.Fungicide, "1ml/L".some, PesticideStatus.Active),
        ("SWITCH 62.5 WG", PesticideType.Fungicide, none[PesticideInfo], PesticideStatus.Active),
        ("VERTAB", PesticideType.Insecticide, "0.8ml/L".some, PesticideStatus.Active),
        ("SIMFONIA", PesticideType.Insecticide, "organic".some, PesticideStatus.Active),
        ("SPRUZIT AF Neudorff", PesticideType.Insecticide, none[PesticideInfo], PesticideStatus.Active),
        ("MOSPILAN 20SG", PesticideType.Insecticide, none[PesticideInfo], PesticideStatus.Active),
        ("Neem oil + Catille soap", PesticideType.Insecticide, "5ml:5ml:1L".some, PesticideStatus.Active),
        ("H2O2", PesticideType.Treatment, none[PesticideInfo], PesticideStatus.Active)
      )

      assertEquals(missing, GetPesticideResult.RecordMissing)
      seeded match
        case CatalogReadResult.Read(pesticides) =>
          val actualSeeded = pesticides.map(p => (p.data.name.value, p.data.pesticideType, p.data.maybeInfo.map(_.value), p.status))
          assertEquals(actualSeeded, expectedSeeded)
        case other => fail(s"expected Read, got $other")
      assertEquals(added, CatalogAddResult.Added(pesticide))
      assertEquals(afterAdd, GetPesticideResult.Read(pesticide))
      assertEquals(editResult, UpdatePesticideResult.Updated)
      assertEquals(archiveResult, UpdatePesticideResult.Updated)
      assertEquals(afterArchive, GetPesticideResult.Read(archived))

  test("should report an update failure for an unknown pesticide"):
    Using.resource(storeResource): resource =>
      val result = resource.store.updatePesticide(pesticide)

      result match
        case UpdatePesticideResult.UpdateFailed(_) => ()
        case other                                 => fail(s"expected UpdateFailed, got $other")

  test("should reject invalid stored pesticide values"):
    Using.resource(storeResource): resource =>
      execute(resource.dataSource, "update pesticide set id = 'xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx' where name = 'H2O2'")

      val actualError = intercept[DatabaseCorruption](resource.store.getPesticides).err.getMessage

      assertEquals(actualError, "invalid pesticide id: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx")

    Using.resource(storeResource): resource =>
      val rejected = intercept[java.sql.SQLException]:
        execute(resource.dataSource, "update pesticide set type = 'Unknown' where name = 'H2O2'")

      assert(rejected.getMessage.contains("CHECK constraint failed"))

  test("should report read and write failures when the database is read-only"):
    Using.resource(storeResource): resource =>
      val readOnlyStore = SqlitePesticideStore.make(Transactor(resource.dataSource, connectionConfig = makeReadOnly))

      val addResult    = readOnlyStore.addPesticide(pesticide)
      val updateResult = readOnlyStore.updatePesticide(pesticide)

      addResult match
        case CatalogAddResult.AddFailed(_) => ()
        case other                         => fail(s"expected AddFailed, got $other")
      updateResult match
        case UpdatePesticideResult.UpdateFailed(_) => ()
        case other                                 => fail(s"expected UpdateFailed, got $other")

  test("should report read failures when the pesticide schema is unavailable"):
    Using.resource(Sqlite.make.connect(SqliteLocation.InMemory(UUID.randomUUID().toString))): connection =>
      val store = SqlitePesticideStore.make(connection.transactor)

      val readResult = store.getPesticides
      val getResult  = store.getPesticide(pesticideId)

      readResult match
        case CatalogReadResult.ReadFailed(_) => ()
        case other                           => fail(s"expected ReadFailed, got $other")
      getResult match
        case GetPesticideResult.ReadFailed(_) => ()
        case other                            => fail(s"expected ReadFailed, got $other")

  private case class StoreResource(
      connection: SqliteConnection,
      dataSource: DataSource,
      store: PesticideStore
  ) extends AutoCloseable:
    override def close(): Unit = connection.close()

  private def storeResource =
    val connection = Sqlite.make.connect(SqliteLocation.InMemory(UUID.randomUUID().toString))
    val _          = Flyway.configure().dataSource(connection.dataSource).load().migrate()
    StoreResource(connection, connection.dataSource, buildStore(connection))

  private def buildStore(connection: SqliteConnection) =
    SqlitePesticideStore.make(connection.transactor)

  private def makeReadOnly(connection: Connection) =
    val statement = connection.createStatement()
    val _         = statement.execute("PRAGMA query_only = ON")
    statement.close()

  private def execute(dataSource: DataSource, sql: String) =
    val connection = dataSource.getConnection()
    try
      val statement = connection.prepareStatement(sql)
      try
        val _ = statement.executeUpdate()
      finally statement.close()
    finally connection.close()
