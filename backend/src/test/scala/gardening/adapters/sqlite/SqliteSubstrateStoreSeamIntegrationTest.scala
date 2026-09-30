package gardening.adapters.sqlite

import cats.syntax.option.*
import io.github.iltotore.iron.autoRefine
import com.augustnagro.magnum.Transactor
import gardening.domain.*
import gardening.domain.substrate.{AddSubstrateComponentResult, DeleteSubstrateMixResult, GetSubstrateComponentResult, GetSubstrateComponentsResult, GetSubstrateMixesResult, SaveSubstrateMixResult, UpdateSubstrateComponentResult}
import gardening.ports.SubstrateStore
import gardening.adapters.sqlite.SqliteHelpers.{execute, makeReadOnly}
import org.flywaydb.core.Flyway

import java.util.UUID
import javax.sql.DataSource
import scala.util.Using

class SqliteSubstrateStoreSeamIntegrationTest extends munit.FunSuite:

  private val componentId   = SubstrateComponentId(UUID.fromString("10000000-0000-4000-8000-000000000001"))
  private val perliteId     = SubstrateComponentId(UUID.fromString("00000000-0000-4000-8000-000000000003"))
  private val componentData = SubstrateComponentData(SubstrateComponentName("Pumice"), SubstrateComponentInfo("porous").some)
  private val component     = SubstrateComponent(componentId, componentData, SubstrateComponentStatus.Active)

  private val mixId     = UUID.fromString("20000000-0000-4000-8000-000000000001")
  private val substrate = Substrate.of(List(SubstratePart(perliteId, share = 100))).getOrElse(fail("invalid test substrate"))
  private val mix       = SubstrateMix(mixId, SubstrateMixName("Cactus mix"), SubstrateMixNotes("Free-draining").some, substrate)

  test("should persist additions, edits, and archiving"):
    Using.resource(storeResource): resource =>
      val store   = resource.store
      val missing = store.getSubstrateComponent(componentId)
      val empty   = store.getSubstrateComponents

      val added         = store.addSubstrateComponent(component)
      val afterAdd      = store.getSubstrateComponent(componentId)
      val editedData    = SubstrateComponentData(SubstrateComponentName("Fine pumice"), none)
      val edited        = component.copy(data = editedData)
      val editResult    = store.updateSubstrateComponent(edited)
      val archived      = edited.copy(status = SubstrateComponentStatus.Archived)
      val archiveResult = store.updateSubstrateComponent(archived)
      val afterArchive  = store.getSubstrateComponent(componentId)

      assertEquals(missing, GetSubstrateComponentResult.RecordMissing)
      assertEquals(empty, GetSubstrateComponentsResult.Read(Vector.empty))
      assertEquals(added, AddSubstrateComponentResult.Added(component))
      assertEquals(afterAdd, GetSubstrateComponentResult.Read(component))
      assertEquals(editResult, UpdateSubstrateComponentResult.Updated)
      assertEquals(archiveResult, UpdateSubstrateComponentResult.Updated)
      assertEquals(afterArchive, GetSubstrateComponentResult.Read(archived))

  test("should report an update failure for an unknown substrate component"):
    Using.resource(storeResource): resource =>
      val result = resource.store.updateSubstrateComponent(component)

      result match
        case UpdateSubstrateComponentResult.UpdateFailed(_) => ()
        case other                                          => fail(s"expected UpdateFailed, got $other")

  test("should report invalid stored component identifiers"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      val _          = resource.store.addSubstrateComponent(component)
      execute(dataSource, "update substrate_component set id = 'xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx' where name = 'Pumice'")

      val actualError = intercept[DatabaseCorruption](resource.store.getSubstrateComponents).err.getMessage

      assertEquals(actualError, "invalid substrate component id: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx")

  test("should reject invalid stored substrate component status"):
    Using.resource(storeResource): resource =>
      val _        = resource.store.addSubstrateComponent(component)
      val rejected = intercept[java.sql.SQLException]:
        execute(resource.dataSource, "update substrate_component set status = 'Unknown' where name = 'Pumice'")

      assert(rejected.getMessage.contains("CHECK constraint failed"))

  test("should report write failures when the database is read-only"):
    Using.resource(storeResource): resource =>
      val readOnlyStore = SqliteSubstrateStore.make(Transactor(resource.dataSource, connectionConfig = makeReadOnly))

      val addResult    = readOnlyStore.addSubstrateComponent(component)
      val updateResult = readOnlyStore.updateSubstrateComponent(component)

      addResult match
        case AddSubstrateComponentResult.AddFailed(_) => ()
        case other                                    => fail(s"expected AddFailed, got $other")
      updateResult match
        case UpdateSubstrateComponentResult.UpdateFailed(_) => ()
        case other                                          => fail(s"expected UpdateFailed, got $other")

  test("should report read failures when the substrate schema is unavailable"):
    Using.resource(Sqlite.make(SqliteHelpers.lockTimeout).connect(SqliteLocation.InMemory(UUID.randomUUID().toString))): connection =>
      val store = SqliteSubstrateStore.make(connection.transactor)

      val readResult = store.getSubstrateComponents
      val getResult  = store.getSubstrateComponent(componentId)

      readResult match
        case GetSubstrateComponentsResult.ReadFailed(_) => ()
        case other                                      => fail(s"expected ReadFailed, got $other")
      getResult match
        case GetSubstrateComponentResult.ReadFailed(_) => ()
        case other                                     => fail(s"expected ReadFailed, got $other")

  test("should persist and permanently delete substrate mixes"):
    Using.resource(storeResource): resource =>
      val store = resource.store

      val emptyRead   = store.getSubstrateMixes
      val added       = store.saveSubstrateMix(mix)
      val afterAdd    = store.getSubstrateMixes
      val deleted     = store.deleteSubstrateMix(mixId)
      val afterDelete = store.getSubstrateMixes

      assertEquals(emptyRead, GetSubstrateMixesResult.Read(Vector.empty))
      assertEquals(added, SaveSubstrateMixResult.Saved(mix))
      assertEquals(afterAdd, GetSubstrateMixesResult.Read(Vector(mix)))
      assertEquals(deleted, DeleteSubstrateMixResult.Deleted)
      assertEquals(afterDelete, GetSubstrateMixesResult.Read(Vector.empty))

  test("should permanently delete a substrate mix that no longer exists"):
    Using.resource(storeResource): resource =>
      val result = resource.store.deleteSubstrateMix(UUID.randomUUID())

      assertEquals(result, DeleteSubstrateMixResult.Deleted)

  test("should report invalid stored substrate mix identifiers"):
    Using.resource(storeResource): resource =>
      val store      = resource.store
      val dataSource = resource.dataSource
      val _          = store.saveSubstrateMix(mix)
      execute(dataSource, s"update substrate_mix set id = 'xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx' where id = '$mixId'")

      val actualError = intercept[DatabaseCorruption](store.getSubstrateMixes).err.getMessage

      assertEquals(actualError, "invalid substrate mix id: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx")

  test("should report invalid stored substrate mix substrate"):
    Using.resource(storeResource): resource =>
      val store      = resource.store
      val dataSource = resource.dataSource
      val _          = store.saveSubstrateMix(mix)
      execute(dataSource, s"update substrate_mix set substrate = '[]' where id = '$mixId'")

      val actualError = intercept[DatabaseCorruption](store.getSubstrateMixes).err.getMessage

      assert(actualError.startsWith("invalid stored substrate mix substrate: "), s"unexpected message: $actualError")

  test("should report substrate mix write and delete failures when the database is read-only"):
    Using.resource(storeResource): resource =>
      val readOnlyStore = SqliteSubstrateStore.make(Transactor(resource.dataSource, connectionConfig = makeReadOnly))

      val addResult    = readOnlyStore.saveSubstrateMix(mix)
      val deleteResult = readOnlyStore.deleteSubstrateMix(mixId)

      addResult match
        case SaveSubstrateMixResult.SaveFailed(_) => ()
        case other                                => fail(s"expected SaveFailed, got $other")
      deleteResult match
        case DeleteSubstrateMixResult.DeleteFailed(_) => ()
        case other                                    => fail(s"expected DeleteFailed, got $other")

  test("should report a substrate mix read failure when the substrate schema is unavailable"):
    Using.resource(Sqlite.make(SqliteHelpers.lockTimeout).connect(SqliteLocation.InMemory(UUID.randomUUID().toString))): connection =>
      val store = SqliteSubstrateStore.make(connection.transactor)

      val result = store.getSubstrateMixes

      result match
        case GetSubstrateMixesResult.ReadFailed(_) => ()
        case other                                 => fail(s"expected ReadFailed, got $other")

  private case class StoreResource(
      connection: SqliteConnection,
      dataSource: DataSource,
      store: SubstrateStore
  ) extends AutoCloseable:
    override def close(): Unit = connection.close()

  private def storeResource =
    val connection = Sqlite.make(SqliteHelpers.lockTimeout).connect(SqliteLocation.InMemory(UUID.randomUUID().toString))
    val _          = Flyway.configure().dataSource(connection.dataSource).load().migrate()
    StoreResource(connection, connection.dataSource, buildStore(connection))

  private def buildStore(connection: SqliteConnection) =
    SqliteSubstrateStore.make(connection.transactor)
