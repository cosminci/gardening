package gardening.adapters.persistence

import cats.syntax.option.*
import com.augustnagro.magnum.Transactor
import gardening.domain.*
import gardening.domain.catalog.*
import gardening.domain.pesticide.PesticideStore
import org.flywaydb.core.Flyway

import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource
import scala.util.Using

class SqlitePesticideStoreSeamIntegrationTest extends munit.FunSuite:

  private val pesticideId   = PesticideId(UUID.fromString("10000000-0000-4000-8000-000000000002"))
  private val pesticideData = PesticideData(NomenclatureName("Sulfur"), PesticideType.Fungicide, none)
  private val pesticide     = Pesticide(pesticideId, pesticideData)

  test("should read seeded pesticides and persist additions and edits"):
    Using.resource(storeResource): resource =>
      val store  = resource.store
      val seeded = store.getPesticides

      val added       = store.addPesticide(pesticide)
      val afterAdd    = store.getPesticides
      val editedData  = PesticideData(NomenclatureName("Wettable sulfur"), PesticideType.Treatment, NomenclatureInfo("2g/L").some)
      val edited      = store.editPesticide(pesticideId, editedData)
      val missing     = store.editPesticide(PesticideId(UUID.randomUUID()), editedData)
      val afterUpdate = store.getPesticides

      val expectedSeeded = Vector(
        ("ORTIVA TOP", PesticideType.Fungicide, "1ml/L".some),
        ("SWITCH 62.5 WG", PesticideType.Fungicide, none),
        ("VERTAB", PesticideType.Insecticide, "0.8ml/L".some),
        ("SIMFONIA", PesticideType.Insecticide, "organic".some),
        ("SPRUZIT AF Neudorff", PesticideType.Insecticide, none),
        ("MOSPILAN 20SG", PesticideType.Insecticide, none),
        ("Neem oil + Catille soap", PesticideType.Insecticide, "5ml:5ml:1L".some),
        ("H2O2", PesticideType.Treatment, none)
      )
      seeded match
        case CatalogReadResult.Read(pesticides) =>
          val actualSeeded =
            pesticides.map(pesticide => (pesticide.data.name.value, pesticide.data.pesticideType, pesticide.data.maybeInfo.map(_.value)))
          assertEquals(actualSeeded, expectedSeeded)
        case other => fail(s"expected Read, got $other")
      assertEquals(added, CatalogAddResult.Added(pesticide))
      afterAdd match
        case CatalogReadResult.Read(pesticides) => assertEquals(pesticides.lastOption, pesticide.some)
        case other                              => fail(s"expected Read, got $other")
      assertEquals(edited, CatalogEditResult.Edited(pesticide.copy(data = editedData)))
      assertEquals(missing, CatalogEditResult.RecordMissing)
      afterUpdate match
        case CatalogReadResult.Read(pesticides) => assertEquals(pesticides.lastOption.map(_.data), editedData.some)
        case other                              => fail(s"expected Read, got $other")

  test("should reject invalid stored pesticide values"):
    Using.resource(storeResource): resource =>
      execute(resource.dataSource, "update pesticide set id = 'xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx' where name = 'H2O2'")

      val actualError = intercept[DatabaseCorruption](resource.store.getPesticides).err.getMessage

      assertEquals(actualError, "invalid pesticide id: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx")

    Using.resource(storeResource): resource =>
      val rejected = intercept[java.sql.SQLException]:
        execute(resource.dataSource, "update pesticide set type = 'Unknown' where name = 'H2O2'")

      assert(rejected.getMessage.contains("CHECK constraint failed"))

  test("should report write failures when the database is read-only"):
    Using.resource(storeResource): resource =>
      val readOnlyStore = SqlitePesticideStore.make(Transactor(resource.dataSource, connectionConfig = makeReadOnly))

      val addResult  = readOnlyStore.addPesticide(pesticide)
      val editResult = readOnlyStore.editPesticide(pesticideId, pesticideData)

      addResult match
        case CatalogAddResult.AddFailed(_) => ()
        case other                         => fail(s"expected AddFailed, got $other")
      editResult match
        case CatalogEditResult.EditFailed(_) => ()
        case other                           => fail(s"expected EditFailed, got $other")

  test("should report read failures when the pesticide schema is unavailable"):
    Using.resource(Sqlite.make.connect(SqliteLocation.InMemory(UUID.randomUUID().toString))): connection =>
      val store = SqlitePesticideStore.make(connection.transactor)

      val result = store.getPesticides

      result match
        case CatalogReadResult.ReadFailed(_) => ()
        case other                           => fail(s"expected ReadFailed, got $other")

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
