package gardening.adapters.sqlite

import cats.syntax.option.*
import com.augustnagro.magnum.Transactor
import gardening.domain.*
import gardening.domain.pesticide.{AddPesticideResult, GetPesticideResult, GetPesticidesResult, UpdatePesticideResult}
import gardening.ports.PesticideStore
import gardening.adapters.sqlite.SqliteHelpers.{execute, makeReadOnly}
import org.flywaydb.core.Flyway

import java.util.UUID
import javax.sql.DataSource
import scala.util.Using

class SqlitePesticideStoreSeamIntegrationTest extends munit.FunSuite:

  private val pesticideId   = PesticideId(UUID.fromString("10000000-0000-4000-8000-000000000002"))
  private val pesticideData = PesticideData(PesticideName("Sulfur"), PesticideType.Fungicide, PesticideInfo("apply weekly").some)
  private val pesticide     = Pesticide(pesticideId, pesticideData, PesticideStatus.Active)

  test("should persist additions, edits, and archiving"):
    Using.resource(storeResource): resource =>
      val store   = resource.store
      val missing = store.getPesticide(pesticideId)
      val empty   = store.getPesticides

      val added         = store.addPesticide(pesticide)
      val afterAdd      = store.getPesticide(pesticideId)
      val editedData    = PesticideData(PesticideName("Wettable sulfur"), PesticideType.Treatment, PesticideInfo("2g/L").some)
      val edited        = pesticide.copy(data = editedData)
      val editResult    = store.updatePesticide(edited)
      val archived      = edited.copy(status = PesticideStatus.Archived)
      val archiveResult = store.updatePesticide(archived)
      val afterArchive  = store.getPesticide(pesticideId)

      assertEquals(missing, GetPesticideResult.RecordMissing)
      assertEquals(empty, GetPesticidesResult.Read(Vector.empty))
      assertEquals(added, AddPesticideResult.Added(pesticide))
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
      val _ = resource.store.addPesticide(pesticide)
      execute(resource.dataSource, "update pesticide set id = 'xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx' where name = 'Sulfur'")

      val actualError = intercept[DatabaseCorruption](resource.store.getPesticides).err.getMessage

      assertEquals(actualError, "invalid pesticide id: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx")

    Using.resource(storeResource): resource =>
      val _        = resource.store.addPesticide(pesticide)
      val rejected = intercept[java.sql.SQLException]:
        execute(resource.dataSource, "update pesticide set type = 'Unknown' where name = 'Sulfur'")

      assert(rejected.getMessage.contains("CHECK constraint failed"))

  test("should report read and write failures when the database is read-only"):
    Using.resource(storeResource): resource =>
      val readOnlyStore = SqlitePesticideStore.make(Transactor(resource.dataSource, connectionConfig = makeReadOnly))

      val addResult    = readOnlyStore.addPesticide(pesticide)
      val updateResult = readOnlyStore.updatePesticide(pesticide)

      addResult match
        case AddPesticideResult.AddFailed(_) => ()
        case other                           => fail(s"expected AddFailed, got $other")
      updateResult match
        case UpdatePesticideResult.UpdateFailed(_) => ()
        case other                                 => fail(s"expected UpdateFailed, got $other")

  test("should report read failures when the pesticide schema is unavailable"):
    Using.resource(Sqlite.make.connect(SqliteLocation.InMemory(UUID.randomUUID().toString))): connection =>
      val store = SqlitePesticideStore.make(connection.transactor)

      val readResult = store.getPesticides
      val getResult  = store.getPesticide(pesticideId)

      readResult match
        case GetPesticidesResult.ReadFailed(_) => ()
        case other                             => fail(s"expected ReadFailed, got $other")
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
