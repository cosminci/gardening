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
import scala.util.Using

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

  private val perliteSubstrate    = Substrate.of(List(SubstratePart(perliteId, share = 100))).getOrElse(fail("invalid perlite substrate"))
  private val sand3to5Substrate   = Substrate.of(List(SubstratePart(sand3to5Id, share = 100))).getOrElse(fail("invalid sand substrate"))
  private val lecaSubstrate       = Substrate.of(List(SubstratePart(lecaId, share = 100))).getOrElse(fail("invalid LECA substrate"))
  private val defaultPlantDetails = PlantDetails(Species("Ficus lyrata"), none, Location("Balcony"), perliteSubstrate, PlantStatus.Active)
  private val care = OperationDetails.Care(Set(ActionType.Watered, ActionType.Fertilized), Set.empty, MoistureLevel.Wet, Note("a little dry").some)

  test("should return no attention samples when none have been seeded"):
    Using.resource(storeResource): resource =>
      val store = resource.store
      assertEquals(store.getAttentionSamples(size = 20), GetAttentionSamplesResult.Read(Vector.empty))

  test("should return a plant with its persisted substrate"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      val store      = resource.store
      seedPlant(dataSource, id = "p1", maybeNickname = "Fig".some, substrate = List(perliteId -> 100))

      val expectedDetails = PlantDetails(Species("Ficus lyrata"), Nickname("Fig").some, Location("Balcony"), perliteSubstrate, PlantStatus.Active)
      val expectedResult  = GetPlantResult.Read(Plant(PlantId("p1"), expectedDetails))

      assertEquals(store.getPlant(PlantId("p1")), expectedResult)
      assertEquals(store.getPlant(PlantId("missing")), GetPlantResult.RecordMissing)

  test("should fail when stored plant data is corrupt"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      val store      = resource.store
      seedPlant(dataSource, id = "duplicate-components", substrate = List(perliteId -> 60, perliteId -> 60))

      val corruption = intercept[DatabaseCorruption](store.getPlant(PlantId("duplicate-components")))
      assertEquals(corruption.err.getMessage, "invalid stored substrate: DecodingFailure at : DuplicateComponent")

  test("should round-trip a care operation without changing plant substrate"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      val store      = resource.store
      seedPlant(dataSource, id = "p1")
      val operation = Operation(OperationId("o1"), PlantId("p1"), date, care.copy(pesticides = Set(vertabId, neemOilId)))

      assertEquals(store.addOperation(operation), LogOperationResult.Logged(operation.id))

      assertEquals(queryString(dataSource, "select kind from operation where id = ?", operation.id.value), "Care")
      assertEquals(store.getOperations(PlantId("p1"), fullWindow), GetOperationsResult.Read(OperationPage(Vector(operation), hasNextPage = false)))
      assertEquals(store.getPlant(PlantId("p1")), GetPlantResult.Read(Plant(PlantId("p1"), defaultPlantDetails)))

  test("should page operations by timestamp descending with an identifier tie-breaker"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      val store      = resource.store
      seedPlant(dataSource, id = "p1")
      val first      = Operation(OperationId("o1"), PlantId("p1"), date, care)
      val second     = Operation(OperationId("o2"), PlantId("p1"), date.plusMillis(100), care)
      val third      = Operation(OperationId("o3"), PlantId("p1"), date.plusNanos(100_500_000), care)
      val fourth     = Operation(OperationId("o4"), PlantId("p1"), date.plusNanos(100_500_000), care)
      val operations = Vector(first, second, third, fourth)
      operations.foreach(operation => assertEquals(store.addOperation(operation), LogOperationResult.Logged(operation.id)))

      assertEquals(
        store.getOperations(PlantId("p1"), OperationWindow(offset = 0, size = 3)),
        GetOperationsResult.Read(OperationPage(Vector(fourth, third, second), hasNextPage = true))
      )
      assertEquals(
        store.getOperations(PlantId("p1"), OperationWindow(offset = 3, size = 3)),
        GetOperationsResult.Read(OperationPage(Vector(first), hasNextPage = false))
      )
      assertEquals(
        store.getOperations(PlantId("p1"), OperationWindow(offset = 4, size = 3)),
        GetOperationsResult.Read(OperationPage(Vector.empty, hasNextPage = false))
      )

  test("should read bounded watering dates for every active plant"):
    Using.resource(storeResource): resource =>
      val dataSource      = resource.dataSource
      val store           = resource.store
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

      val firstWateringHistory  = WateringHistory.from(firstWaterings.reverse.take(20).map(_.date)).fold(message => fail(message), identity)
      val secondWateringHistory = WateringHistory.from(secondWaterings.reverse.map(_.date)).fold(message => fail(message), identity)
      val emptyWateringHistory  = WateringHistory.from(Vector.empty).fold(message => fail(message), identity)
      val expectedSamples       = Vector(
        PlantAttentionSample(Plant(firstPlantId, defaultPlantDetails), firstWateringHistory),
        PlantAttentionSample(Plant(secondPlantId, defaultPlantDetails), secondWateringHistory),
        PlantAttentionSample(Plant(PlantId("no-waterings"), defaultPlantDetails), emptyWateringHistory)
      )
      assertEquals(store.getAttentionSamples(size = 20), GetAttentionSamplesResult.Read(expectedSamples))

  test("should report malformed watering dates through the attention read"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      val store      = resource.store
      seedPlant(dataSource, id = "p1")
      val watering = Operation(OperationId("watering"), PlantId("p1"), date, care)
      assertEquals(store.addOperation(watering), LogOperationResult.Logged(watering.id))
      execute(dataSource, "update operation set date = 'today' where id = ?", watering.id.value)

      store.getAttentionSamples(size = 20) match
        case GetAttentionSamplesResult.ReadFailed(DatabaseCorruption(reason)) =>
          assertEquals(reason.getMessage, "invalid stored operation date: today")
        case other => fail(s"expected ReadFailed, got $other")

  test("should report archived plant corruption through the attention read"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      val store      = resource.store
      seedPlant(dataSource, id = "archived", status = PlantStatus.Archived)
      execute(dataSource, "update plant set substrate = '[]' where id = ?", "archived")

      store.getAttentionSamples(size = 20) match
        case GetAttentionSamplesResult.ReadFailed(DatabaseCorruption(reason)) =>
          assertEquals(reason.getMessage, "invalid stored substrate: DecodingFailure at : Empty")
        case other => fail(s"expected ReadFailed, got $other")

  test("should retain chronological operation order after migrating existing timestamps"):
    Using.resource(Sqlite.make.connect(SqliteLocation.InMemory(UUID.randomUUID().toString))): connection =>
      val _ = Flyway.configure().dataSource(connection.dataSource).target(MigrationVersion.fromVersion("2")).load().migrate()
      seedPlant(connection.dataSource, id = "p1")

      val details = OperationDetails.Care(Set.empty, Set.empty, MoistureLevel.Wet, none)
      val older   = Operation(OperationId("o1"), PlantId("p1"), date, details)
      val newer   = Operation(OperationId("o2"), PlantId("p1"), date.plusMillis(100), details)
      val payload = """{"actions":[],"pesticides":[],"moisture":"Wet","note":null}"""
      execute(
        connection.dataSource,
        "insert into operation (id, plant_id, date, kind, payload) values (?, ?, ?, ?, ?)",
        older.id.value,
        older.plantId.value,
        older.date.toString,
        "Care",
        payload
      )
      execute(
        connection.dataSource,
        "insert into operation (id, plant_id, date, kind, payload) values (?, ?, ?, ?, ?)",
        newer.id.value,
        newer.plantId.value,
        newer.date.toString,
        "Care",
        payload
      )

      val _     = Flyway.configure().dataSource(connection.dataSource).load().migrate()
      val store = SqlitePlantStore.make(connection.transactor)
      assertEquals(store.getOperations(PlantId("p1"), fullWindow), GetOperationsResult.Read(OperationPage(Vector(newer, older), hasNextPage = false)))

  test("should round-trip a care observation without actions"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      val store      = resource.store
      seedPlant(dataSource, id = "p1")
      val observation = Operation(OperationId("o1"), PlantId("p1"), date, OperationDetails.Care(Set.empty, Set.empty, care.moisture, none))

      assertEquals(store.addOperation(observation), LogOperationResult.Logged(observation.id))
      assertEquals(store.getOperations(PlantId("p1"), fullWindow), GetOperationsResult.Read(OperationPage(Vector(observation), hasNextPage = false)))

  test("should persist a repot without changing the plant"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      val store      = resource.store
      seedPlant(dataSource, id = "p1")
      val operation = Operation(OperationId("o1"), PlantId("p1"), date, OperationDetails.Repot(sand3to5Substrate, Note("new mix").some))

      assertEquals(store.addOperation(operation), LogOperationResult.Logged(operation.id))
      assertEquals(queryString(dataSource, "select kind from operation where id = ?", operation.id.value), "Repot")
      assertEquals(store.getOperation(operation.id), GetOperationResult.Read(operation))
      assertEquals(store.getPlant(PlantId("p1")), GetPlantResult.Read(Plant(PlantId("p1"), defaultPlantDetails)))

  test("should reject unknown operation kinds and malformed JSON payloads"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      seedPlant(dataSource, id = "p1")

      val unknownKind = intercept[java.sql.SQLException]:
        execute(
          dataSource,
          "insert into operation (id, plant_id, date, kind, payload) values (?, ?, ?, ?, ?)",
          "unknown-kind",
          "p1",
          date.toString,
          "Unknown",
          "{}"
        )
      val malformedCarePayload = intercept[java.sql.SQLException]:
        execute(
          dataSource,
          "insert into operation (id, plant_id, date, kind, payload) values (?, ?, ?, ?, ?)",
          "malformed-payload",
          "p1",
          date.toString,
          "Care",
          "not-json"
        )
      val malformedRepotPayload = intercept[java.sql.SQLException]:
        execute(
          dataSource,
          "insert into operation (id, plant_id, date, kind, payload) values (?, ?, ?, ?, ?)",
          "malformed-repot",
          "p1",
          date.plusNanos(1).toString,
          "Repot",
          "not-json"
        )

      assert(unknownKind.getMessage.contains("CHECK constraint failed"))
      assert(malformedCarePayload.getMessage.contains("CHECK constraint failed"))
      assert(malformedRepotPayload.getMessage.contains("CHECK constraint failed"))

  test("should update every editable plant detail"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      val store      = resource.store
      seedPlant(dataSource, id = "p1")
      val updatedSubstrate = lecaSubstrate
      val updatedNickname  = Nickname("Monty").some
      val updatedDetails   =
        PlantDetails(Species("Monstera deliciosa"), updatedNickname, Location("Living room"), updatedSubstrate, PlantStatus.Archived)
      val updatedPlant = Plant(PlantId("p1"), updatedDetails)

      assertEquals(store.updatePlant(updatedPlant), UpdatePlantResult.Updated)
      assertEquals(store.getPlant(updatedPlant.id), GetPlantResult.Read(updatedPlant))

  test("should return no operation for an unknown id"):
    Using.resource(storeResource): resource =>
      val store = resource.store
      assertEquals(store.getOperation(OperationId("missing")), GetOperationResult.RecordMissing)
      assertEquals(store.getOperations(PlantId("missing"), fullWindow), GetOperationsResult.Read(OperationPage(Vector.empty, hasNextPage = false)))

  test("should fail when stored operation data is corrupt"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      val store      = resource.store
      seedPlant(dataSource, id = "p1")
      val repot = Operation(OperationId("o1"), PlantId("p1"), date, OperationDetails.Repot(perliteSubstrate, none))
      assertEquals(store.addOperation(repot), LogOperationResult.Logged(repot.id))
      val repotPayload = s"""{"substrate":[{"component":"${perliteId.value}","share":60},{"component":"${perliteId.value}","share":60}]}"""
      execute(dataSource, "update operation set payload = ? where id = ?", repotPayload, "o1")

      val pageCorruption = intercept[DatabaseCorruption](store.getOperations(PlantId("p1"), fullWindow))
      assertEquals(pageCorruption.err.getMessage, "invalid stored operation payload: DecodingFailure at .substrate: DuplicateComponent")

      val singleCorruption = intercept[DatabaseCorruption](store.getOperation(repot.id))
      assertEquals(singleCorruption.err.getMessage, "invalid stored operation payload: DecodingFailure at .substrate: DuplicateComponent")

      val careOperation = Operation(OperationId("o2"), PlantId("p1"), date.plusNanos(1), care)
      assertEquals(store.addOperation(careOperation), LogOperationResult.Logged(careOperation.id))
      val carePayload = """{"actions":["Unknown"],"pesticides":[],"moisture":"Wet","note":null}"""
      execute(dataSource, "update operation set payload = ? where id = ?", carePayload, careOperation.id.value)

      val invalidCare = intercept[DatabaseCorruption](store.getOperation(careOperation.id))
      assertEquals(invalidCare.err.getMessage, "invalid stored operation payload: DecodingFailure at .actions[0]: invalid action: Unknown")

  test("should amend a repot without changing the plant"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      val store      = resource.store
      seedPlant(dataSource, id = "p1")
      val oldSubstrate = sand3to5Substrate
      val operation    = Operation(OperationId("o1"), PlantId("p1"), date, OperationDetails.Repot(oldSubstrate, none))
      val newSubstrate = lecaSubstrate
      val amended      = OperationDetails.Repot(newSubstrate, maybeNote = none)
      assertEquals(store.addOperation(operation), LogOperationResult.Logged(operation.id))

      assertEquals(store.updateOperation(operation.id, amended), EditOperationResult.Edited(operation.copy(details = amended)))
      assertEquals(store.getPlant(PlantId("p1")), GetPlantResult.Read(Plant(PlantId("p1"), defaultPlantDetails)))

  test("should remove and restore operations for compensation"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      val store      = resource.store
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
    Using.resource(storeResource): resource =>
      val store   = resource.store
      val missing = Operation(OperationId("missing"), PlantId("p1"), date, care)
      store.restoreOperation(missing) match
        case OperationCompensationResult.CompensationFailed(reason) => assertEquals(reason.getMessage, "operation not found while restoring: missing")
        case other                                                  => fail(s"expected CompensationFailed, got $other")
      store.updatePlant(Plant(PlantId("missing"), defaultPlantDetails)) match
        case UpdatePlantResult.UpdateFailed(reason) => assertEquals(reason.getMessage, "plant not found while updating: missing")
        case other                                  => fail(s"expected UpdateFailed, got $other")

  test("should fail when edited operation metadata is corrupt"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      val store      = resource.store
      seedPlant(dataSource, id = "p1")
      val payload = """{"actions":[],"moisture":"Wet","note":null}"""
      execute(dataSource, "insert into operation (id, plant_id, date, kind, payload) values (?, ?, ?, ?, ?)", "o1", "p1", "today", "Care", payload)

      val failure = intercept[DatabaseCorruption](store.updateOperation(OperationId("o1"), care))
      assertEquals(failure.err.getMessage, "invalid stored operation date: today")

  test("should report a logging failure when the plant does not exist"):
    Using.resource(storeResource): resource =>
      val store = resource.store
      store.addOperation(Operation(OperationId("o1"), PlantId("no-such-plant"), date, care)) match
        case LogOperationResult.LoggingFailed(_) => ()
        case other                               => fail(s"expected LoggingFailed, got $other")

  test("should report a missing operation when editing an unknown id"):
    Using.resource(storeResource): resource =>
      val store = resource.store
      assertEquals(store.updateOperation(OperationId("nope"), care), EditOperationResult.OperationMissing)

  test("should edit an existing care operation without changing plant substrate"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      val store      = resource.store
      seedPlant(dataSource, id = "p1")
      val operation = Operation(OperationId("o1"), PlantId("p1"), date, care)
      val amended   = OperationDetails.Care(care.actions, care.pesticides, MoistureLevel.Dry, none)
      assertEquals(store.addOperation(operation), LogOperationResult.Logged(operation.id))

      assertEquals(store.updateOperation(operation.id, amended), EditOperationResult.Edited(operation.copy(details = amended)))
      assertEquals(store.getPlant(PlantId("p1")), GetPlantResult.Read(Plant(PlantId("p1"), defaultPlantDetails)))

  test("should seed, add, and edit substrate components"):
    Using.resource(storeResource): resource =>
      val store              = resource.store
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
    Using.resource(storeResource): resource =>
      val store = resource.store
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
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      val store      = resource.store
      execute(dataSource, "update substrate_component set id = 'xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx' where name = 'Perlite'")
      execute(dataSource, "update pesticide set id = 'xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx' where name = 'H2O2'")

      val actualError = intercept[DatabaseCorruption](store.getSubstrateComponents).err.getMessage
      assertEquals(actualError, "invalid substrate component id: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx")
      assertEquals(intercept[DatabaseCorruption](store.getPesticides).err.getMessage, "invalid pesticide id: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx")

    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      val rejected   = intercept[java.sql.SQLException]:
        execute(dataSource, "update pesticide set type = 'Unknown' where name = 'H2O2'")
      assert(rejected.getMessage.contains("CHECK constraint failed"))

  test("should report write failures when the database is read-only"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      val store      = resource.store
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
      readOnlyStore.updatePlant(Plant(PlantId("p1"), defaultPlantDetails)) match
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
    Using.resource(Sqlite.make.connect(SqliteLocation.InMemory(UUID.randomUUID().toString))): connection =>
      val store = SqlitePlantStore.make(connection.transactor)
      store.getOperations(PlantId("p1"), fullWindow) match
        case GetOperationsResult.ReadFailed(_) => ()
        case other                             => fail(s"expected ReadFailed, got $other")
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

  final private case class StoreResource(
      connection: SqliteConnection,
      dataSource: DataSource,
      store: PlantJournalStore & PlantAttentionStore
  ) extends AutoCloseable:
    override def close(): Unit = connection.close()

  private def storeResource =
    val connection = Sqlite.make.connect(SqliteLocation.InMemory(UUID.randomUUID().toString))
    val _          = Flyway.configure().dataSource(connection.dataSource).load().migrate()
    StoreResource(connection, connection.dataSource, buildStore(connection))

  private def buildStore(connection: SqliteConnection) =
    SqlitePlantStore.make(connection.transactor)

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
