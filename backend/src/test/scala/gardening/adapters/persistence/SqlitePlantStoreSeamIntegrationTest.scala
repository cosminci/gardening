package gardening.adapters.persistence

import cats.syntax.option.*
import gardening.domain.*
import gardening.domain.attention.*
import gardening.domain.journal.*
import io.github.iltotore.iron.autoRefine
import munit.FunSuite
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion

import com.augustnagro.magnum.Transactor
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class SqlitePlantStoreSeamIntegrationTest extends FunSuite:

  private val date        = Instant.parse("2026-01-01T00:00:00Z")
  private val componentId = SubstrateComponentId(UUID.fromString("10000000-0000-4000-8000-000000000001"))
  private val pesticideId = PesticideId(UUID.fromString("10000000-0000-4000-8000-000000000002"))
  private val perliteId   = SubstrateComponentId(UUID.fromString("00000000-0000-4000-8000-000000000003"))
  private val sand3to5Id  = SubstrateComponentId(UUID.fromString("00000000-0000-4000-8000-000000000005"))
  private val lecaId      = SubstrateComponentId(UUID.fromString("00000000-0000-4000-8000-000000000007"))
  private val vertabId    = PesticideId(UUID.fromString("00000000-0000-4000-8001-000000000003"))
  private val neemOilId   = PesticideId(UUID.fromString("00000000-0000-4000-8001-000000000007"))
  private val fullWindow  = OperationWindow(offset = 0, size = 10)

  private val care = OperationDetails.Care(Set(ActionType.Watered, ActionType.Fertilized), Set.empty, MoistureLevel.Wet, Note("a little dry").some)

  test("should return no plants when none have been seeded"):
    withStore: (_, store) =>
      assertEquals(store.getPlants, GetPlantsResult.Read(Vector.empty))
      assertEquals(store.getAttentionSamples(size = 20), GetAttentionSamplesResult.Read(Vector.empty))

  test("should return an active plant with its persisted substrate"):
    withStore: (dataSource, store) =>
      seedPlant(dataSource, id = "p1", maybeNickname = "Fig".some, substrate = List(perliteId -> 100))

      store.getPlants match
        case GetPlantsResult.Read(Vector(plant)) =>
          assertEquals(plant.id, PlantId("p1"))
          assertEquals(plant.details.maybeNickname, Nickname("Fig").some)
          assertEquals(plant.details.substrate.parts, List(SubstratePart(perliteId, share = 100)))
          assertEquals(store.getPlant(PlantId("p1")), GetPlantResult.Read(plant))
          assertEquals(store.getPlant(PlantId("missing")), GetPlantResult.RecordMissing)
        case other => fail(s"expected one plant, got $other")

  test("should exclude archived plants"):
    withStore: (dataSource, store) =>
      seedPlant(dataSource, id = "active-1", status = PlantStatus.Active)
      seedPlant(dataSource, id = "archived-1", status = PlantStatus.Archived)

      store.getPlants match
        case GetPlantsResult.Read(plants) => assertEquals(plants.map(_.id), Vector(PlantId("active-1")))
        case other                        => fail(s"expected Read, got $other")

  test("should fail when stored plant data is corrupt"):
    withStore: (dataSource, store) =>
      seedPlant(dataSource, id = "duplicate-components", substrate = List(perliteId -> 60, perliteId -> 60))

      val allPlants = intercept[DatabaseCorruption](store.getPlants)
      assertEquals(allPlants.err.getMessage, "invalid stored substrate: DecodingFailure at : DuplicateComponent")

      val plant = intercept[DatabaseCorruption](store.getPlant(PlantId("duplicate-components")))
      assertEquals(plant.err.getMessage, "invalid stored substrate: DecodingFailure at : DuplicateComponent")

  test("should round-trip a care operation without changing plant substrate"):
    withStore: (dataSource, store) =>
      seedPlant(dataSource, id = "p1")
      val operation = Operation(OperationId("o1"), PlantId("p1"), date, care.copy(pesticides = Set(vertabId, neemOilId)))

      assertEquals(store.addOperation(operation), LogOperationResult.Logged(operation.id))

      assertEquals(readOperationKind(dataSource, operation.id.value), "Care")
      assertEquals(store.getOperations(PlantId("p1"), fullWindow), GetOperationsResult.Read(OperationPage(Vector(operation), hasNextPage = false)))
      store.getPlants match
        case GetPlantsResult.Read(Vector(plant)) => assertEquals(plant.details.substrate.parts, List(SubstratePart(perliteId, share = 100)))
        case other                               => fail(s"expected one plant, got $other")

  test("should page operations by timestamp descending with an identifier tie-breaker"):
    withStore: (dataSource, store) =>
      seedPlant(dataSource, id = "p1")
      val operations = Vector(
        Operation(OperationId("o1"), PlantId("p1"), date, care),
        Operation(OperationId("o2"), PlantId("p1"), date.plusMillis(100), care),
        Operation(OperationId("o3"), PlantId("p1"), date.plusNanos(100_500_000), care),
        Operation(OperationId("o4"), PlantId("p1"), date.plusNanos(100_500_000), care)
      )
      operations.foreach(operation => assertEquals(store.addOperation(operation), LogOperationResult.Logged(operation.id)))

      assertEquals(
        store.getOperations(PlantId("p1"), OperationWindow(offset = 0, size = 3)),
        GetOperationsResult.Read(OperationPage(Vector(operations(3), operations(2), operations(1)), hasNextPage = true))
      )
      assertEquals(
        store.getOperations(PlantId("p1"), OperationWindow(offset = 3, size = 3)),
        GetOperationsResult.Read(OperationPage(Vector(operations.head), hasNextPage = false))
      )
      assertEquals(
        store.getOperations(PlantId("p1"), OperationWindow(offset = 4, size = 3)),
        GetOperationsResult.Read(OperationPage(Vector.empty, hasNextPage = false))
      )

  test("should read bounded watering dates for every active plant"):
    withStore: (dataSource, store) =>
      val firstPlantId    = PlantId("p1")
      val secondPlantId   = PlantId("p2")
      val archivedPlantId = PlantId("archived")
      seedPlant(dataSource, id = firstPlantId.value)
      seedPlant(dataSource, id = secondPlantId.value)
      seedPlant(dataSource, id = archivedPlantId.value, status = PlantStatus.Archived)
      seedPlant(dataSource, id = "no-waterings")

      val firstWaterings = Vector.tabulate(22): index =>
        Operation(OperationId(f"first-$index%02d"), firstPlantId, date.plusSeconds(index.toLong), care)
      val secondWaterings = Vector.tabulate(3): index =>
        Operation(OperationId(f"second-$index%02d"), secondPlantId, date, care)
      val archivedWatering = Operation(OperationId("archived-watering"), archivedPlantId, date, care)
      val nonWatering      = Operation(OperationId("care-only"), firstPlantId, date.plusSeconds(30), care.copy(actions = Set(ActionType.Pruned)))
      (firstWaterings ++ secondWaterings :+ archivedWatering :+ nonWatering).foreach: operation =>
        assertEquals(store.addOperation(operation), LogOperationResult.Logged(operation.id))

      val expectedSamples = Vector(
        PlantAttentionSample(plant(firstPlantId), firstWaterings.reverse.take(20).map(_.date)),
        PlantAttentionSample(plant(secondPlantId), secondWaterings.reverse.map(_.date)),
        PlantAttentionSample(plant(PlantId("no-waterings")), Vector.empty)
      )
      assertEquals(store.getAttentionSamples(size = 20), GetAttentionSamplesResult.Read(expectedSamples))

  test("should report archived plant corruption through the attention read"):
    withStore: (dataSource, store) =>
      seedPlant(dataSource, id = "archived", status = PlantStatus.Archived)
      execute(dataSource, "update plant set substrate = '[]' where id = ?", "archived")

      store.getAttentionSamples(size = 20) match
        case GetAttentionSamplesResult.ReadFailed(DatabaseCorruption(reason)) =>
          assertEquals(reason.getMessage, "invalid stored substrate: DecodingFailure at : Empty")
        case other => fail(s"expected ReadFailed, got $other")

  test("should retain chronological operation order after migrating existing timestamps"):
    val connection = Sqlite.connect(SqliteLocation.InMemory(UUID.randomUUID().toString))
    try
      val _ = Flyway.configure().dataSource(connection.dataSource).target(MigrationVersion.fromVersion("2")).load().migrate()
      seedPlant(connection.dataSource, id = "p1")

      val details = OperationDetails.Care(Set.empty, Set.empty, MoistureLevel.Wet, none)
      val older   = Operation(OperationId("o1"), PlantId("p1"), date, details)
      val newer   = Operation(OperationId("o2"), PlantId("p1"), date.plusMillis(100), details)
      val payload = """{"actions":[],"pesticides":[],"moisture":"Wet","note":null}"""
      insertOperation(connection.dataSource, older.id.value, older.plantId.value, older.date.toString, "Care", payload)
      insertOperation(connection.dataSource, newer.id.value, newer.plantId.value, newer.date.toString, "Care", payload)

      val _     = Flyway.configure().dataSource(connection.dataSource).load().migrate()
      val store = SqlitePlantStore.make(connection.transactor)
      assertEquals(store.getOperations(PlantId("p1"), fullWindow), GetOperationsResult.Read(OperationPage(Vector(newer, older), hasNextPage = false)))
    finally connection.close()

  test("should round-trip a care observation without actions"):
    withStore: (dataSource, store) =>
      seedPlant(dataSource, id = "p1")
      val observation = Operation(OperationId("o1"), PlantId("p1"), date, OperationDetails.Care(Set.empty, Set.empty, care.moisture, none))

      assertEquals(store.addOperation(observation), LogOperationResult.Logged(observation.id))
      assertEquals(store.getOperations(PlantId("p1"), fullWindow), GetOperationsResult.Read(OperationPage(Vector(observation), hasNextPage = false)))

  test("should persist a repot without changing the plant"):
    withStore: (dataSource, store) =>
      seedPlant(dataSource, id = "p1")
      val substrate = substrateOf(sand3to5Id -> 100)
      val operation = Operation(OperationId("o1"), PlantId("p1"), date, OperationDetails.Repot(substrate, Note("new mix").some))

      assertEquals(store.addOperation(operation), LogOperationResult.Logged(operation.id))
      assertEquals(readOperationKind(dataSource, operation.id.value), "Repot")
      assertEquals(store.getOperation(operation.id), GetOperationResult.Read(operation))
      store.getPlants match
        case GetPlantsResult.Read(Vector(plant)) => assertEquals(plant.details.substrate.parts, List(SubstratePart(perliteId, share = 100)))
        case other                               => fail(s"expected one plant, got $other")

  test("should reject unknown operation kinds and malformed JSON payloads"):
    withStore: (dataSource, _) =>
      seedPlant(dataSource, id = "p1")

      val unknownKind = intercept[java.sql.SQLException]:
        insertOperation(dataSource, "unknown-kind", "p1", date.toString, "Unknown", "{}")
      val malformedCarePayload = intercept[java.sql.SQLException]:
        insertOperation(dataSource, "malformed-payload", "p1", date.toString, "Care", "not-json")
      val malformedRepotPayload = intercept[java.sql.SQLException]:
        insertOperation(dataSource, "malformed-repot", "p1", date.plusNanos(1).toString, "Repot", "not-json")

      assert(unknownKind.getMessage.contains("CHECK constraint failed"))
      assert(malformedCarePayload.getMessage.contains("CHECK constraint failed"))
      assert(malformedRepotPayload.getMessage.contains("CHECK constraint failed"))

  test("should update every editable plant detail"):
    withStore: (dataSource, store) =>
      seedPlant(dataSource, id = "p1")
      val updatedSubstrate = substrateOf(lecaId -> 100)
      val updatedNickname  = Nickname("Monty").some
      val updatedDetails   =
        PlantDetails(Species("Monstera deliciosa"), updatedNickname, Location("Living room"), updatedSubstrate, PlantStatus.Archived)
      val updatedPlant = Plant(PlantId("p1"), updatedDetails)

      assertEquals(store.updatePlant(updatedPlant), UpdatePlantResult.Updated)
      assertEquals(store.getPlant(updatedPlant.id), GetPlantResult.Read(updatedPlant))
      assertEquals(store.getPlants, GetPlantsResult.Read(Vector.empty))

  test("should return no operation for an unknown id"):
    withStore: (_, store) =>
      assertEquals(store.getOperation(OperationId("missing")), GetOperationResult.RecordMissing)
      assertEquals(store.getOperations(PlantId("missing"), fullWindow), GetOperationsResult.Read(OperationPage(Vector.empty, hasNextPage = false)))

  test("should fail when stored operation data is corrupt"):
    withStore: (dataSource, store) =>
      seedPlant(dataSource, id = "p1")
      val repot = Operation(OperationId("o1"), PlantId("p1"), date, OperationDetails.Repot(substrateOf(perliteId -> 100), none))
      assertEquals(store.addOperation(repot), LogOperationResult.Logged(repot.id))
      val repotPayload = s"""{"substrate":[{"component":"${perliteId.value}","share":60},{"component":"${perliteId.value}","share":60}]}"""
      updateOperationPayload(dataSource, id = "o1", payload = repotPayload)

      val operations = intercept[DatabaseCorruption](store.getOperations(PlantId("p1"), fullWindow))
      assertEquals(operations.err.getMessage, "invalid stored operation payload: DecodingFailure at .substrate: DuplicateComponent")

      val operation = intercept[DatabaseCorruption](store.getOperation(repot.id))
      assertEquals(operation.err.getMessage, "invalid stored operation payload: DecodingFailure at .substrate: DuplicateComponent")

      val careOperation = Operation(OperationId("o2"), PlantId("p1"), date.plusNanos(1), care)
      assertEquals(store.addOperation(careOperation), LogOperationResult.Logged(careOperation.id))
      val carePayload = """{"actions":["Unknown"],"pesticides":[],"moisture":"Wet","note":null}"""
      updateOperationPayload(dataSource, id = careOperation.id.value, payload = carePayload)

      val invalidCare = intercept[DatabaseCorruption](store.getOperation(careOperation.id))
      assertEquals(invalidCare.err.getMessage, "invalid stored operation payload: DecodingFailure at .actions[0]: invalid action: Unknown")

  test("should amend a repot without changing the plant"):
    withStore: (dataSource, store) =>
      seedPlant(dataSource, id = "p1")
      val oldSubstrate = substrateOf(sand3to5Id -> 100)
      val operation    = Operation(OperationId("o1"), PlantId("p1"), date, OperationDetails.Repot(oldSubstrate, none))
      val newSubstrate = substrateOf(lecaId -> 100)
      val amended      = OperationDetails.Repot(newSubstrate, maybeNote = none)
      assertEquals(store.addOperation(operation), LogOperationResult.Logged(operation.id))

      assertEquals(store.updateOperation(operation.id, amended), EditOperationResult.Edited(operation.copy(details = amended)))
      store.getPlants match
        case GetPlantsResult.Read(Vector(plant)) => assertEquals(plant.details.substrate.parts, List(SubstratePart(perliteId, share = 100)))
        case other                               => fail(s"expected one plant, got $other")

  test("should remove and restore operations for compensation"):
    withStore: (dataSource, store) =>
      seedPlant(dataSource, id = "p1")
      val original = Operation(OperationId("o1"), PlantId("p1"), date, care)
      val amended  = OperationDetails.Care(Set.empty, Set.empty, MoistureLevel.Dry, none)
      assertEquals(store.addOperation(original), LogOperationResult.Logged(original.id))
      assertEquals(store.updateOperation(original.id, amended), EditOperationResult.Edited(original.copy(details = amended)))

      assertEquals(store.restoreOperation(original), OperationCompensationResult.Compensated)
      assertEquals(store.getOperation(original.id), GetOperationResult.Read(original))
      assertEquals(store.removeOperation(original.id), OperationCompensationResult.Compensated)
      assertEquals(store.getOperation(original.id), GetOperationResult.RecordMissing)
      assertEquals(store.removeOperation(original.id), OperationCompensationResult.Compensated)

  test("should report missing compensation targets"):
    withStore: (_, store) =>
      val missing = Operation(OperationId("missing"), PlantId("p1"), date, care)
      store.restoreOperation(missing) match
        case OperationCompensationResult.CompensationFailed(reason) => assertEquals(reason.getMessage, "operation not found while restoring: missing")
        case other                                                  => fail(s"expected CompensationFailed, got $other")
      store.updatePlant(plant(PlantId("missing"))) match
        case UpdatePlantResult.UpdateFailed(reason) => assertEquals(reason.getMessage, "plant not found while updating: missing")
        case other                                  => fail(s"expected UpdateFailed, got $other")

  test("should fail when edited operation metadata is corrupt"):
    withStore: (dataSource, store) =>
      seedPlant(dataSource, id = "p1")
      val payload = """{"actions":[],"moisture":"Wet","note":null}"""
      insertOperation(dataSource, id = "o1", plantId = "p1", storedDate = "today", kind = "Care", payload)

      val failure = intercept[DatabaseCorruption](store.updateOperation(OperationId("o1"), care))
      assertEquals(failure.err.getMessage, "invalid stored operation date: today")

  test("should report a logging failure when the plant does not exist"):
    withStore: (_, store) =>
      store.addOperation(Operation(OperationId("o1"), PlantId("no-such-plant"), date, care)) match
        case LogOperationResult.LoggingFailed(_) => ()
        case other                               => fail(s"expected LoggingFailed, got $other")

  test("should report a missing operation when editing an unknown id"):
    withStore: (_, store) =>
      assertEquals(store.updateOperation(OperationId("nope"), care), EditOperationResult.OperationMissing)

  test("should edit an existing care operation without changing plant substrate"):
    withStore: (dataSource, store) =>
      seedPlant(dataSource, id = "p1")
      val operation = Operation(OperationId("o1"), PlantId("p1"), date, care)
      val amended   = OperationDetails.Care(care.actions, care.pesticides, MoistureLevel.Dry, none)
      assertEquals(store.addOperation(operation), LogOperationResult.Logged(operation.id))

      assertEquals(store.updateOperation(operation.id, amended), EditOperationResult.Edited(operation.copy(details = amended)))
      store.getPlants match
        case GetPlantsResult.Read(Vector(plant)) => assertEquals(plant.details.substrate.parts, List(SubstratePart(perliteId, share = 100)))
        case other                               => fail(s"expected one plant, got $other")

  test("should seed, add, and edit substrate components"):
    withStore: (_, store) =>
      val expectedComponents =
        Vector("Kekkila universal peat", "Kekkila ericaceous peat", "Perlite", "Pine bark", "Sand 3-5 mm", "Sand 4-8 mm", "LECA")
      store.getSubstrateComponents match
        case CatalogReadResult.Read(components) => assertEquals(components.map(_.data.name.value), expectedComponents)
        case other                              => fail(s"expected Read, got $other")

      val added = SubstrateComponent(componentId, SubstrateComponentData(NomenclatureName("Pumice"), NomenclatureInfo("porous").some))
      assertEquals(store.addSubstrateComponent(added), CatalogAddResult.Added(added))
      store.getSubstrateComponents match
        case CatalogReadResult.Read(components) => assertEquals(components.lastOption.map(_.data), added.data.some)
        case other                              => fail(s"expected Read, got $other")

      val editedData = SubstrateComponentData(NomenclatureName("Fine pumice"), none)
      assertEquals(store.editSubstrateComponent(componentId, editedData), CatalogEditResult.Edited(added.copy(data = editedData)))
      assertEquals(store.editSubstrateComponent(SubstrateComponentId(UUID.randomUUID()), editedData), CatalogEditResult.RecordMissing)
      store.getSubstrateComponents match
        case CatalogReadResult.Read(components) => assertEquals(components.lastOption.map(_.data), editedData.some)
        case other                              => fail(s"expected Read, got $other")

  test("should seed, add, and edit pesticides"):
    withStore: (_, store) =>
      store.getPesticides match
        case CatalogReadResult.Read(pesticides) =>
          assertEquals(
            pesticides.map(pesticide => (pesticide.data.name.value, pesticide.data.pesticideType, pesticide.data.maybeInfo.map(_.value))),
            Vector(
              ("ORTIVA TOP", PesticideType.Fungicide, "1ml/L".some),
              ("SWITCH 62.5 WG", PesticideType.Fungicide, none),
              ("VERTAB", PesticideType.Insecticide, "0.8ml/L".some),
              ("SIMFONIA", PesticideType.Insecticide, "organic".some),
              ("SPRUZIT AF Neudorff", PesticideType.Insecticide, none),
              ("MOSPILAN 20SG", PesticideType.Insecticide, none),
              ("Neem oil + Catille soap", PesticideType.Insecticide, "5ml:5ml:1L".some),
              ("H2O2", PesticideType.Treatment, none)
            )
          )
        case other => fail(s"expected Read, got $other")

      val added = Pesticide(pesticideId, PesticideData(NomenclatureName("Sulfur"), PesticideType.Fungicide, none))
      assertEquals(store.addPesticide(added), CatalogAddResult.Added(added))

      val editedData = PesticideData(NomenclatureName("Wettable sulfur"), PesticideType.Treatment, NomenclatureInfo("2g/L").some)
      assertEquals(store.editPesticide(pesticideId, editedData), CatalogEditResult.Edited(added.copy(data = editedData)))
      assertEquals(store.editPesticide(PesticideId(UUID.randomUUID()), editedData), CatalogEditResult.RecordMissing)

  test("should reject corrupt catalog values"):
    withStore: (dataSource, store) =>
      execute(dataSource, "update substrate_component set id = 'xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx' where name = 'Perlite'")
      execute(dataSource, "update pesticide set id = 'xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx' where name = 'H2O2'")

      val actualError = intercept[DatabaseCorruption](store.getSubstrateComponents).err.getMessage
      assertEquals(actualError, "invalid substrate component id: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx")
      assertEquals(intercept[DatabaseCorruption](store.getPesticides).err.getMessage, "invalid pesticide id: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx")

    withStore: (dataSource, _) =>
      val rejected = intercept[java.sql.SQLException]:
        execute(dataSource, "update pesticide set type = 'Unknown' where name = 'H2O2'")
      assert(rejected.getMessage.contains("CHECK constraint failed"))

  test("should report write failures when the database is read-only"):
    withStore: (dataSource, store) =>
      seedPlant(dataSource, id = "p1")
      val operation = Operation(OperationId("o1"), PlantId("p1"), date, care)
      val component = SubstrateComponent(componentId, SubstrateComponentData(NomenclatureName("Pumice"), none))
      val pesticide = Pesticide(pesticideId, PesticideData(NomenclatureName("Sulfur"), PesticideType.Fungicide, none))
      assertEquals(store.addOperation(operation), LogOperationResult.Logged(operation.id))
      val readOnlyStore = SqlitePlantStore.make(Transactor(dataSource, connectionConfig = makeReadOnly))

      readOnlyStore.updateOperation(operation.id, care) match
        case EditOperationResult.EditFailed(_) => ()
        case other                             => fail(s"expected EditFailed, got $other")
      List(readOnlyStore.removeOperation(operation.id), readOnlyStore.restoreOperation(operation)).foreach:
        case OperationCompensationResult.CompensationFailed(_) => ()
        case other                                             => fail(s"expected CompensationFailed, got $other")
      readOnlyStore.updatePlant(plant()) match
        case UpdatePlantResult.UpdateFailed(_) => ()
        case other                             => fail(s"expected UpdateFailed, got $other")
      readOnlyStore.addSubstrateComponent(component) match
        case CatalogAddResult.AddFailed(_) => ()
        case other                         => fail(s"expected AddFailed, got $other")
      readOnlyStore.editSubstrateComponent(perliteId, component.data) match
        case CatalogEditResult.EditFailed(_) => ()
        case other                           => fail(s"expected EditFailed, got $other")
      readOnlyStore.addPesticide(pesticide) match
        case CatalogAddResult.AddFailed(_) => ()
        case other                         => fail(s"expected AddFailed, got $other")
      readOnlyStore.editPesticide(pesticideId, pesticide.data) match
        case CatalogEditResult.EditFailed(_) => ()
        case other                           => fail(s"expected EditFailed, got $other")

  test("should return read failures when the journal schema is unavailable"):
    val connection = Sqlite.connect(SqliteLocation.InMemory(UUID.randomUUID().toString))
    try
      val store = SqlitePlantStore.make(connection.transactor)
      List(store.getPlants, store.getOperations(PlantId("p1"), fullWindow)).foreach:
        case GetPlantsResult.ReadFailed(_) | GetOperationsResult.ReadFailed(_) => ()
        case other                                                             => fail(s"expected ReadFailed, got $other")
      store.getPlant(PlantId("p1")) match
        case GetPlantResult.ReadFailed(_) => ()
        case other                        => fail(s"expected ReadFailed, got $other")
      store.getOperation(OperationId("o1")) match
        case GetOperationResult.ReadFailed(_) => ()
        case other                            => fail(s"expected ReadFailed, got $other")
      store.getAttentionSamples(size = 20) match
        case GetAttentionSamplesResult.ReadFailed(_) => ()
        case other                                   => fail(s"expected ReadFailed, got $other")
      store.getSubstrateComponents match
        case CatalogReadResult.ReadFailed(_) => ()
        case other                           => fail(s"expected ReadFailed, got $other")
      store.getPesticides match
        case CatalogReadResult.ReadFailed(_) => ()
        case other                           => fail(s"expected ReadFailed, got $other")
    finally connection.close()

  private def withStore(test: (DataSource, PlantJournalStore & PlantAttentionStore) => Unit) =
    val connection = Sqlite.connect(SqliteLocation.InMemory(UUID.randomUUID().toString))
    try
      val _ = Flyway.configure().dataSource(connection.dataSource).load().migrate()
      test(connection.dataSource, SqlitePlantStore.make(connection.transactor))
    finally connection.close()

  private def makeReadOnly(connection: Connection) =
    val statement = connection.createStatement()
    val _         = statement.execute("PRAGMA query_only = ON")
    statement.close()

  private def seedPlant(
      dataSource: DataSource,
      id: String,
      species: String = "Ficus lyrata",
      maybeNickname: Option[String] = none,
      location: String = "Balcony",
      status: PlantStatus = PlantStatus.Active,
      substrate: List[(SubstrateComponentId, Int)] = List(perliteId -> 100)
  ) =
    val connection = dataSource.getConnection()
    try
      val statement = connection.prepareStatement("insert into plant (id, species, nickname, location, status, substrate) values (?, ?, ?, ?, ?, ?)")
      statement.setString(1, id)
      statement.setString(2, species)
      maybeNickname.fold(statement.setNull(3, java.sql.Types.VARCHAR))(nickname => statement.setString(3, nickname))
      statement.setString(4, location)
      statement.setString(5, status.toString)
      val substrateJson = substrate.map((component, share) => s"""{"component":"${component.value}","share":$share}""").mkString("[", ",", "]")
      statement.setString(6, substrateJson)
      val _ = statement.executeUpdate()
      statement.close()
    finally connection.close()

  private def substrateOf(parts: (SubstrateComponentId, Percentage)*) =
    Substrate.of(parts.map((component, share) => SubstratePart(component, share)).toList).getOrElse(fail("invalid test substrate"))

  private def plant(id: PlantId = PlantId("p1")) =
    Plant(id, PlantDetails(Species("Ficus lyrata"), none, Location("Balcony"), substrateOf(perliteId -> 100), PlantStatus.Active))

  private def updateOperationPayload(dataSource: DataSource, id: String, payload: String) =
    execute(dataSource, "update operation set payload = ? where id = ?", payload, id)

  private def insertOperation(dataSource: DataSource, id: String, plantId: String, storedDate: String, kind: String, payload: String) =
    val sql = "insert into operation (id, plant_id, date, kind, payload) values (?, ?, ?, ?, ?)"
    execute(dataSource, sql, id, plantId, storedDate, kind, payload)

  private def readOperationKind(dataSource: DataSource, id: String) =
    queryString(dataSource, "select kind from operation where id = ?", id)

  private def execute(dataSource: DataSource, sql: String, parameters: String*) =
    val connection = dataSource.getConnection()
    try
      val statement = connection.prepareStatement(sql)
      try
        parameters.zipWithIndex.foreach((parameter, index) => statement.setString(index + 1, parameter))
        val _ = statement.executeUpdate()
      finally statement.close()
    finally connection.close()

  private def queryString(dataSource: DataSource, sql: String, parameters: String*) =
    val connection = dataSource.getConnection()
    try
      val statement = connection.prepareStatement(sql)
      try
        parameters.zipWithIndex.foreach((parameter, index) => statement.setString(index + 1, parameter))
        val result = statement.executeQuery()
        try
          assert(result.next(), s"expected query to return a row: $sql")
          result.getString(1)
        finally result.close()
      finally statement.close()
    finally connection.close()
