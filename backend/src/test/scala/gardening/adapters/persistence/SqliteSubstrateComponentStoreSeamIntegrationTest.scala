package gardening.adapters.persistence

import cats.syntax.option.*
import com.augustnagro.magnum.Transactor
import gardening.domain.*
import gardening.domain.catalog.*
import gardening.domain.substrate.SubstrateComponentStore
import org.flywaydb.core.Flyway

import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource
import scala.util.Using

class SqliteSubstrateComponentStoreSeamIntegrationTest extends munit.FunSuite:

  private val componentId   = SubstrateComponentId(UUID.fromString("10000000-0000-4000-8000-000000000001"))
  private val perliteId     = SubstrateComponentId(UUID.fromString("00000000-0000-4000-8000-000000000003"))
  private val componentData = SubstrateComponentData(NomenclatureName("Pumice"), NomenclatureInfo("porous").some)
  private val component     = SubstrateComponent(componentId, componentData)

  test("should read seeded components and persist additions and edits"):
    Using.resource(storeResource): resource =>
      val store  = resource.store
      val seeded = store.getSubstrateComponents

      val added       = store.addSubstrateComponent(component)
      val afterAdd    = store.getSubstrateComponents
      val editedData  = SubstrateComponentData(NomenclatureName("Fine pumice"), none)
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
      val readOnlyStore = SqliteSubstrateComponentStore.make(Transactor(resource.dataSource, connectionConfig = makeReadOnly))

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
      val store = SqliteSubstrateComponentStore.make(connection.transactor)

      val result = store.getSubstrateComponents

      result match
        case CatalogReadResult.ReadFailed(_) => ()
        case other                           => fail(s"expected ReadFailed, got $other")

  private case class StoreResource(
      connection: SqliteConnection,
      dataSource: DataSource,
      store: SubstrateComponentStore
  ) extends AutoCloseable:
    override def close(): Unit = connection.close()

  private def storeResource =
    val connection = Sqlite.make.connect(SqliteLocation.InMemory(UUID.randomUUID().toString))
    val _          = Flyway.configure().dataSource(connection.dataSource).load().migrate()
    StoreResource(connection, connection.dataSource, buildStore(connection))

  private def buildStore(connection: SqliteConnection) =
    SqliteSubstrateComponentStore.make(connection.transactor)

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
