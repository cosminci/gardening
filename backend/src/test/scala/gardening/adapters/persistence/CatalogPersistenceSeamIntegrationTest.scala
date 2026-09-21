package gardening.adapters.persistence

import cats.syntax.option.*

import com.augustnagro.magnum.Transactor
import gardening.domain.*
import munit.FunSuite
import org.flywaydb.core.Flyway

import java.sql.Connection
import java.util.UUID

class CatalogPersistenceSeamIntegrationTest extends FunSuite:

  private val componentId = SubstrateComponentId(UUID.fromString("10000000-0000-4000-8000-000000000001"))
  private val pesticideId = PesticideId(UUID.fromString("10000000-0000-4000-8000-000000000002"))

  test("should seed, add, and edit substrate components"):
    withStore: (_, store) =>
      store.getSubstrateComponents match
        case CatalogReadResult.Read(components) =>
          assertEquals(
            components.map(_.data.name.value),
            Vector(
              "Kekkila universal peat",
              "Kekkila ericaceous peat",
              "Perlite",
              "Pine bark",
              "Sand 3-5 mm",
              "Sand 4-8 mm",
              "LECA"
            )
          )
        case other => fail(s"expected Read, got $other")

      val added = SubstrateComponent(
        componentId,
        SubstrateComponentData(NomenclatureName("Pumice"), NomenclatureInfo("porous").some)
      )
      assertEquals(store.addSubstrateComponent(added), CatalogAddResult.Added(added))
      store.getSubstrateComponents match
        case CatalogReadResult.Read(components) =>
          assertEquals(components.lastOption.map(_.data), added.data.some)
        case other => fail(s"expected Read, got $other")
      val editedData = SubstrateComponentData(NomenclatureName("Fine pumice"), none)
      assertEquals(
        store.editSubstrateComponent(componentId, editedData),
        CatalogEditResult.Edited(added.copy(data = editedData))
      )
      assertEquals(
        store.editSubstrateComponent(SubstrateComponentId(UUID.randomUUID()), editedData),
        CatalogEditResult.RecordMissing
      )
      store.getSubstrateComponents match
        case CatalogReadResult.Read(components) =>
          assertEquals(components.lastOption.map(_.data), editedData.some)
        case other => fail(s"expected Read, got $other")

  test("should seed, add, and edit pesticides"):
    withStore: (_, store) =>
      store.getPesticides match
        case CatalogReadResult.Read(pesticides) =>
          assertEquals(
            pesticides.map(pesticide => (pesticide.data.name.value, pesticide.data.pesticideType.value, pesticide.data.maybeInfo.map(_.value))),
            Vector(
              ("ORTIVA TOP", "Fungicide", "1ml/L".some),
              ("SWITCH 62.5 WG", "Fungicide", none),
              ("VERTAB", "Insecticide", "0.8ml/L".some),
              ("SIMFONIA", "Insecticide", "organic".some),
              ("SPRUZIT AF Neudorff", "Insecticide", none),
              ("MOSPILAN 20SG", "Insecticide", none),
              ("Neem oil + Catille soap", "Insecticide", "5ml:5ml:1L".some),
              ("H2O2", "Treatment", none)
            )
          )
        case other => fail(s"expected Read, got $other")

      val added = Pesticide(pesticideId, PesticideData(NomenclatureName("Sulfur"), PesticideType("Fungicide"), none))
      assertEquals(store.addPesticide(added), CatalogAddResult.Added(added))
      val editedData = PesticideData(NomenclatureName("Wettable sulfur"), PesticideType("Treatment"), NomenclatureInfo("2g/L").some)
      assertEquals(store.editPesticide(pesticideId, editedData), CatalogEditResult.Edited(added.copy(data = editedData)))
      assertEquals(
        store.editPesticide(PesticideId(UUID.randomUUID()), editedData),
        CatalogEditResult.RecordMissing
      )

  test("should fail when stored catalog identifiers are corrupt"):
    val connection = connectionWithSchema()
    val store      = SqlitePlantJournalStore.make(connection.transactor)
    execute(connection.dataSource, "update substrate_component set id = 'xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx' where name = 'Perlite'")
    execute(connection.dataSource, "update pesticide set id = 'xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx' where name = 'H2O2'")

    assertEquals(
      intercept[DatabaseCorruption](store.getSubstrateComponents).err.getMessage,
      "invalid substrate component id: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
    )
    assertEquals(intercept[DatabaseCorruption](store.getPesticides).err.getMessage, "invalid pesticide id: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx")

    connection.close()

  test("should report database failures"):
    val connection = connectionWithSchema()
    val store      = SqlitePlantJournalStore.make(connection.transactor)

    val readOnlyStore = SqlitePlantJournalStore.make(Transactor(connection.dataSource, connectionConfig = makeReadOnly))
    val component     = SubstrateComponent(componentId, SubstrateComponentData(NomenclatureName("Pumice"), none))
    val pesticide     = Pesticide(pesticideId, PesticideData(NomenclatureName("Sulfur"), PesticideType("Fungicide"), none))
    readOnlyStore.addSubstrateComponent(component) match
      case CatalogAddResult.AddFailed(_) => ()
      case other                         => fail(s"expected AddFailed, got $other")
    readOnlyStore.editSubstrateComponent(TestNomenclatureIds.Perlite, component.data) match
      case CatalogEditResult.EditFailed(_) => ()
      case other                           => fail(s"expected EditFailed, got $other")
    readOnlyStore.addPesticide(pesticide) match
      case CatalogAddResult.AddFailed(_) => ()
      case other                         => fail(s"expected AddFailed, got $other")
    readOnlyStore.editPesticide(pesticideId, pesticide.data) match
      case CatalogEditResult.EditFailed(_) => ()
      case other                           => fail(s"expected EditFailed, got $other")
    connection.close()
    store.getSubstrateComponents match
      case CatalogReadResult.ReadFailed(_) => ()
      case other                           => fail(s"expected ReadFailed, got $other")
    store.getPesticides match
      case CatalogReadResult.ReadFailed(_) => ()
      case other                           => fail(s"expected ReadFailed, got $other")

  private def withStore(test: (SqliteConnection, PlantJournalStore) => Unit): Unit =
    val connection = connectionWithSchema()
    try test(connection, SqlitePlantJournalStore.make(connection.transactor))
    finally connection.close()

  private def connectionWithSchema(): SqliteConnection =
    val connection = Sqlite.connect(SqliteLocation.InMemory(UUID.randomUUID().toString))
    val _          = Flyway.configure().dataSource(connection.dataSource).load().migrate()
    connection

  private def execute(dataSource: javax.sql.DataSource, sql: String): Unit =
    val connection = dataSource.getConnection()
    try
      val statement = connection.createStatement()
      try
        val _ = statement.executeUpdate(sql)
      finally statement.close()
    finally connection.close()

  private def makeReadOnly(connection: Connection): Unit =
    val statement = connection.createStatement()
    try
      val _ = statement.execute("PRAGMA query_only = ON")
    finally statement.close()
