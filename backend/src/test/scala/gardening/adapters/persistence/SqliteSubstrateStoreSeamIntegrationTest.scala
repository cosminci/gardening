package gardening.adapters.persistence

import cats.syntax.option.*
import io.github.iltotore.iron.autoRefine
import com.augustnagro.magnum.Transactor
import gardening.domain.*
import gardening.domain.catalog.*
import gardening.domain.substrate.SubstrateStore
import org.flywaydb.core.Flyway

import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource
import scala.util.Using

class SqliteSubstrateStoreSeamIntegrationTest extends munit.FunSuite:

  private val componentId   = SubstrateComponentId(UUID.fromString("10000000-0000-4000-8000-000000000001"))
  private val perliteId     = SubstrateComponentId(UUID.fromString("00000000-0000-4000-8000-000000000003"))
  private val componentData = SubstrateComponentData(SubstrateComponentName("Pumice"), SubstrateComponentInfo("porous").some)
  private val component     = SubstrateComponent(componentId, componentData)

  private val mixId     = UUID.fromString("20000000-0000-4000-8000-000000000001")
  private val substrate = Substrate.of(List(SubstratePart(perliteId, share = 100))).getOrElse(fail("invalid test substrate"))
  private val mix       = SubstrateMix(mixId, SubstrateMixName("Cactus mix"), SubstrateMixNotes("Free-draining").some, substrate)

  test("should read seeded components and persist additions and edits"):
    Using.resource(storeResource): resource =>
      val store  = resource.store
      val seeded = store.getSubstrateComponents

      val added       = store.addSubstrateComponent(component)
      val afterAdd    = store.getSubstrateComponents
      val editedData  = SubstrateComponentData(SubstrateComponentName("Fine pumice"), none)
      val edited      = store.editSubstrateComponent(componentId, editedData)
      val missing     = store.editSubstrateComponent(SubstrateComponentId(UUID.randomUUID()), editedData)
      val afterUpdate = store.getSubstrateComponents

      val expectedNames =
        Vector("Kekkila universal peat", "Kekkila ericaceous peat", "Perlite", "Pine bark", "Sand 3-5 mm", "Sand 4-8 mm", "LECA")
      seeded match
        case CatalogReadResult.Read(components) => assertEquals(components.map(_.data.name.value), expectedNames)
        case other                              => fail(s"expected Read, got $other")
      assertEquals(added, CatalogAddResult.Added(component))
      afterAdd match
        case CatalogReadResult.Read(components) => assertEquals(components.lastOption, component.some)
        case other                              => fail(s"expected Read, got $other")
      assertEquals(edited, CatalogEditResult.Edited(component.copy(data = editedData)))
      assertEquals(missing, CatalogEditResult.RecordMissing)
      afterUpdate match
        case CatalogReadResult.Read(components) => assertEquals(components.lastOption.map(_.data), editedData.some)
        case other                              => fail(s"expected Read, got $other")

  test("should report invalid stored component identifiers"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      execute(dataSource, "update substrate_component set id = 'xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx' where name = 'Perlite'")

      val actualError = intercept[DatabaseCorruption](resource.store.getSubstrateComponents).err.getMessage

      assertEquals(actualError, "invalid substrate component id: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx")

  test("should report write failures when the database is read-only"):
    Using.resource(storeResource): resource =>
      val readOnlyStore = SqliteSubstrateStore.make(Transactor(resource.dataSource, connectionConfig = makeReadOnly))

      val addResult  = readOnlyStore.addSubstrateComponent(component)
      val editResult = readOnlyStore.editSubstrateComponent(perliteId, componentData)

      addResult match
        case CatalogAddResult.AddFailed(_) => ()
        case other                         => fail(s"expected AddFailed, got $other")
      editResult match
        case CatalogEditResult.EditFailed(_) => ()
        case other                           => fail(s"expected EditFailed, got $other")

  test("should report a read failure when the substrate schema is unavailable"):
    Using.resource(Sqlite.make.connect(SqliteLocation.InMemory(UUID.randomUUID().toString))): connection =>
      val store = SqliteSubstrateStore.make(connection.transactor)

      val result = store.getSubstrateComponents

      result match
        case CatalogReadResult.ReadFailed(_) => ()
        case other                           => fail(s"expected ReadFailed, got $other")

  test("should persist and permanently delete substrate mixes"):
    Using.resource(storeResource): resource =>
      val store = resource.store

      val emptyRead   = store.getSubstrateMixes
      val added       = store.addSubstrateMix(mix)
      val afterAdd    = store.getSubstrateMixes
      val deleted     = store.deleteSubstrateMix(mixId)
      val afterDelete = store.getSubstrateMixes

      assertEquals(emptyRead, CatalogReadResult.Read(Vector.empty))
      assertEquals(added, CatalogAddResult.Added(mix))
      assertEquals(afterAdd, CatalogReadResult.Read(Vector(mix)))
      assertEquals(deleted, CatalogDeleteResult.Deleted)
      assertEquals(afterDelete, CatalogReadResult.Read(Vector.empty))

  test("should permanently delete a substrate mix that no longer exists"):
    Using.resource(storeResource): resource =>
      val result = resource.store.deleteSubstrateMix(UUID.randomUUID())

      assertEquals(result, CatalogDeleteResult.Deleted)

  test("should report invalid stored substrate mix identifiers"):
    Using.resource(storeResource): resource =>
      val store      = resource.store
      val dataSource = resource.dataSource
      val _          = store.addSubstrateMix(mix)
      execute(dataSource, s"update substrate_mix set id = 'xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx' where id = '$mixId'")

      val actualError = intercept[DatabaseCorruption](store.getSubstrateMixes).err.getMessage

      assertEquals(actualError, "invalid substrate mix id: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx")

  test("should report invalid stored substrate mix substrate"):
    Using.resource(storeResource): resource =>
      val store      = resource.store
      val dataSource = resource.dataSource
      val _          = store.addSubstrateMix(mix)
      execute(dataSource, s"update substrate_mix set substrate = '[]' where id = '$mixId'")

      val actualError = intercept[DatabaseCorruption](store.getSubstrateMixes).err.getMessage

      assert(actualError.startsWith("invalid stored substrate mix substrate: "), s"unexpected message: $actualError")

  test("should report substrate mix write and delete failures when the database is read-only"):
    Using.resource(storeResource): resource =>
      val readOnlyStore = SqliteSubstrateStore.make(Transactor(resource.dataSource, connectionConfig = makeReadOnly))

      val addResult    = readOnlyStore.addSubstrateMix(mix)
      val deleteResult = readOnlyStore.deleteSubstrateMix(mixId)

      addResult match
        case CatalogAddResult.AddFailed(_) => ()
        case other                         => fail(s"expected AddFailed, got $other")
      deleteResult match
        case CatalogDeleteResult.DeleteFailed(_) => ()
        case other                               => fail(s"expected DeleteFailed, got $other")

  test("should report a substrate mix read failure when the substrate schema is unavailable"):
    Using.resource(Sqlite.make.connect(SqliteLocation.InMemory(UUID.randomUUID().toString))): connection =>
      val store = SqliteSubstrateStore.make(connection.transactor)

      val result = store.getSubstrateMixes

      result match
        case CatalogReadResult.ReadFailed(_) => ()
        case other                           => fail(s"expected ReadFailed, got $other")

  private case class StoreResource(
      connection: SqliteConnection,
      dataSource: DataSource,
      store: SubstrateStore
  ) extends AutoCloseable:
    override def close(): Unit = connection.close()

  private def storeResource =
    val connection = Sqlite.make.connect(SqliteLocation.InMemory(UUID.randomUUID().toString))
    val _          = Flyway.configure().dataSource(connection.dataSource).load().migrate()
    StoreResource(connection, connection.dataSource, buildStore(connection))

  private def buildStore(connection: SqliteConnection) =
    SqliteSubstrateStore.make(connection.transactor)

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
