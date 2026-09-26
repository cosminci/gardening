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

class SqlitePlantJournalStoreSeamIntegrationTest extends FunSuite:

  private val date       = Instant.parse("2026-01-01T00:00:00Z")
  private val perliteId  = SubstrateComponentId(UUID.fromString("00000000-0000-4000-8000-000000000003"))
  private val sand3to5Id = SubstrateComponentId(UUID.fromString("00000000-0000-4000-8000-000000000005"))
  private val lecaId     = SubstrateComponentId(UUID.fromString("00000000-0000-4000-8000-000000000007"))
  private val vertabId   = PesticideId(UUID.fromString("00000000-0000-4000-8001-000000000003"))
  private val neemOilId  = PesticideId(UUID.fromString("00000000-0000-4000-8001-000000000007"))
  private val fullWindow = OperationWindow(offset = 0, size = 10)

  private val perliteSubstrate    = Substrate.of(List(SubstratePart(perliteId, share = 100))).getOrElse(fail("invalid perlite substrate"))
  private val sand3to5Substrate   = Substrate.of(List(SubstratePart(sand3to5Id, share = 100))).getOrElse(fail("invalid sand substrate"))
  private val lecaSubstrate       = Substrate.of(List(SubstratePart(lecaId, share = 100))).getOrElse(fail("invalid LECA substrate"))
  private val defaultPlantDetails = PlantDetails(Species("Ficus lyrata"), none, Location("Balcony"), perliteSubstrate, PlantStatus.Active)
  private val care = OperationDetails.Care(Set(ActionType.Watered, ActionType.Fertilized), Set.empty, MoistureLevel.Wet, Note("a little dry").some)

  test("should persist a new active plant without operations"):
    Using.resource(storeResource): resource =>
      val store   = resource.store
      val created = Plant(PlantId("new"), defaultPlantDetails)

      val result     = store.addPlant(created)
      val plants     = store.getPlants(PlantStatus.Active)
      val operations = store.getOperations(created.id, fullWindow)

      assertEquals(result, AddPlantResult.Added)
      assertEquals(plants, GetPlantsResult.Read(Vector(created)))
      assertEquals(operations, GetOperationsResult.Read(OperationPage(Vector.empty, hasNextPage = false)))

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

  test("should list plants by status without decoding records in the other view"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      val store      = resource.store
      seedPlant(dataSource, id = "active")
      seedPlant(dataSource, id = "archived", status = PlantStatus.Archived)

      val activePlant   = Plant(PlantId("active"), defaultPlantDetails)
      val archivedPlant = Plant(PlantId("archived"), defaultPlantDetails.copy(status = PlantStatus.Archived))

      val activeBefore   = store.getPlants(PlantStatus.Active)
      val archivedBefore = store.getPlants(PlantStatus.Archived)
      execute(dataSource, "update plant set substrate = '[]' where id = ?", "archived")

      val activeAfter   = store.getPlants(PlantStatus.Active)
      val archivedAfter = store.getPlants(PlantStatus.Archived)

      assertEquals(activeBefore, GetPlantsResult.Read(Vector(activePlant)))
      assertEquals(archivedBefore, GetPlantsResult.Read(Vector(archivedPlant)))
      assertEquals(activeAfter, GetPlantsResult.Read(Vector(activePlant)))
      archivedAfter match
        case GetPlantsResult.ReadFailed(DatabaseCorruption(reason)) =>
          assertEquals(reason.getMessage, "invalid stored substrate: DecodingFailure at : Empty")
        case other => fail(s"expected ReadFailed, got $other")

  test("should count zero archived plants in an empty journal"):
    Using.resource(storeResource): resource =>
      val store  = resource.store
      val result = store.getArchivedCount

      assertEquals(result, ArchivedCountResult.Counted(0))

  test("should count archived plants without decoding their details"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      seedPlant(dataSource, id = "active")
      seedPlant(dataSource, id = "archived", status = PlantStatus.Archived)
      execute(dataSource, "update plant set substrate = '[]' where id = ?", "archived")

      val store       = resource.store
      val countResult = store.getArchivedCount
      val plantResult = store.getPlants(PlantStatus.Active)

      assertEquals(countResult, ArchivedCountResult.Counted(1))
      assertEquals(plantResult, GetPlantsResult.Read(Vector(Plant(PlantId("active"), defaultPlantDetails))))

  test("should persist an active plant's archive status and retain its operations"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      seedPlant(dataSource, id = "p1")
      val plantId    = PlantId("p1")
      val operation  = Operation(OperationId("o1"), plantId, date, care)
      val laterCare  = operation.copy(id = OperationId("o2"))
      val laterRepot = operation.copy(id = OperationId("o3"), details = OperationDetails.Repot(lecaSubstrate, none))
      val store      = resource.store
      val firstLog   = store.addOperation(operation)

      val archivedPlant = Plant(plantId, defaultPlantDetails.copy(status = PlantStatus.Archived))
      val archived      = store.updatePlant(archivedPlant)
      val missing       = store.updatePlant(archivedPlant.copy(id = PlantId("unknown")))
      val staleUpdate   = store.updatePlant(Plant(plantId, defaultPlantDetails))
      val rejectedCare  = store.addOperation(laterCare)
      val rejectedRepot = store.addOperation(laterRepot)
      val archivedCount = store.getArchivedCount
      val history       = store.getOperations(plantId, fullWindow)
      val activePlants  = store.getPlants(PlantStatus.Active)

      val expectedHistory = GetOperationsResult.Read(OperationPage(Vector(operation), hasNextPage = false))
      assertEquals(firstLog, LogOperationResult.Logged(operation.id))
      assertEquals(archived, UpdatePlantResult.Updated)
      missing match
        case UpdatePlantResult.UpdateFailed(_) => ()
        case other                             => fail(s"expected UpdateFailed, got $other")
      staleUpdate match
        case UpdatePlantResult.UpdateFailed(_) => ()
        case other                             => fail(s"expected UpdateFailed, got $other")
      assertEquals(rejectedCare, LogOperationResult.PlantArchived)
      assertEquals(rejectedRepot, LogOperationResult.PlantArchived)
      assertEquals(archivedCount, ArchivedCountResult.Counted(1))
      assertEquals(history, expectedHistory)
      assertEquals(activePlants, GetPlantsResult.Read(Vector.empty))
      assertEquals(store.getPlant(plantId), GetPlantResult.Read(archivedPlant))

  test("should report corrupt stored plant details as a read failure"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      val store      = resource.store
      seedPlant(dataSource, id = "duplicate-components", substrate = List(perliteId -> 60, perliteId -> 60))

      val result = store.getPlant(PlantId("duplicate-components"))

      result match
        case GetPlantResult.ReadFailed(DatabaseCorruption(reason)) =>
          assertEquals(reason.getMessage, "invalid stored substrate: DecodingFailure at : DuplicateComponent")
        case other => fail(s"expected ReadFailed, got $other")

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
      val logged     = operations.map(store.addOperation)

      val firstPage  = store.getOperations(PlantId("p1"), OperationWindow(offset = 0, size = 3))
      val secondPage = store.getOperations(PlantId("p1"), OperationWindow(offset = 3, size = 3))
      val finalPage  = store.getOperations(PlantId("p1"), OperationWindow(offset = 4, size = 3))

      val expectedFirst  = GetOperationsResult.Read(OperationPage(Vector(fourth, third, second), hasNextPage = true))
      val expectedSecond = GetOperationsResult.Read(OperationPage(Vector(first), hasNextPage = false))
      val expectedFinal  = GetOperationsResult.Read(OperationPage(Vector.empty, hasNextPage = false))
      assertEquals(logged, operations.map(operation => LogOperationResult.Logged(operation.id)))
      assertEquals(firstPage, expectedFirst)
      assertEquals(secondPage, expectedSecond)
      assertEquals(finalPage, expectedFinal)

  test("should read earliest and latest dates across all archived operations without decoding history"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      seedPlant(dataSource, id = "archived")
      seedPlant(dataSource, id = "other")
      val archivedId = PlantId("archived")
      val earliest   = date.minusSeconds(60)
      val latest     = date.plusSeconds(60)
      val recorded   = Vector.tabulate(12)(index =>
        Operation(OperationId(s"a-$index"), archivedId, date.plusSeconds(index.toLong), care)
      )
      val first          = Operation(OperationId("first"), archivedId, earliest, care)
      val last           = Operation(OperationId("last"), archivedId, latest, care)
      val otherOperation = Operation(OperationId("other"), PlantId("other"), date.plusSeconds(9999), care)
      val operations     = recorded :+ first :+ last
      val store          = resource.store
      val logged         = operations.map(store.addOperation)
      val otherLog       = store.addOperation(otherOperation)
      val archived       = store.updatePlant(Plant(archivedId, defaultPlantDetails.copy(status = PlantStatus.Archived)))
      execute(dataSource, "update operation set payload = '{\"malformed\":true}' where id = ?", "a-5")

      val actual  = store.getOperationDateRange(archivedId)
      val missing = store.getOperationDateRange(PlantId("missing"))

      val expected = GetOperationDateRangeResult.Read(OperationDateRange.Recorded(earliest, latest))
      assertEquals(logged, operations.map(operation => LogOperationResult.Logged(operation.id)))
      assertEquals(otherLog, LogOperationResult.Logged(otherOperation.id))
      assertEquals(archived, UpdatePlantResult.Updated)
      assertEquals(actual, expected)
      assertEquals(missing, GetOperationDateRangeResult.PlantMissing)

  test("should return an empty date range for a plant without operations"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      seedPlant(dataSource, id = "archived", status = PlantStatus.Archived)

      val result = resource.store.getOperationDateRange(PlantId("archived"))

      assertEquals(result, GetOperationDateRangeResult.Read(OperationDateRange.Empty))

  test("should show the same first and last date for one recorded operation"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      seedPlant(dataSource, id = "single")
      val single = Operation(OperationId("single"), PlantId("single"), date, care)

      val store    = resource.store
      val logged   = store.addOperation(single)
      val archived = store.updatePlant(Plant(single.plantId, defaultPlantDetails.copy(status = PlantStatus.Archived)))
      val actual   = store.getOperationDateRange(single.plantId)

      val expected = GetOperationDateRangeResult.Read(OperationDateRange.Recorded(date, date))
      assertEquals(logged, LogOperationResult.Logged(single.id))
      assertEquals(archived, UpdatePlantResult.Updated)
      assertEquals(actual, expected)

  test("should order archived care dates by instant across timestamp precisions"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      seedPlant(dataSource, id = "archived")
      val plantId = PlantId("archived")
      val first   = Operation(OperationId("first"), plantId, date, care)
      val last    = Operation(OperationId("last"), plantId, date, care)

      val store    = resource.store
      val firstLog = store.addOperation(first)
      val lastLog  = store.addOperation(last)
      val archived = store.updatePlant(Plant(plantId, defaultPlantDetails.copy(status = PlantStatus.Archived)))
      execute(dataSource, "update operation set date = '2026-01-01T00:00:00Z' where id = ?", first.id.value)
      execute(dataSource, "update operation set date = '2026-01-01T00:00:00.001Z' where id = ?", last.id.value)
      val actual = store.getOperationDateRange(plantId)

      val expected = GetOperationDateRangeResult.Read(
        OperationDateRange.Recorded(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00.001Z"))
      )
      assertEquals(firstLog, LogOperationResult.Logged(first.id))
      assertEquals(lastLog, LogOperationResult.Logged(last.id))
      assertEquals(archived, UpdatePlantResult.Updated)
      assertEquals(actual, expected)

  test("should report a malformed operation date instead of presenting a partial range"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      val plantId    = PlantId("malformed")
      val first      = Operation(OperationId("first"), plantId, date.minusSeconds(60), care)
      val middle     = Operation(OperationId("middle"), plantId, date, care)
      val last       = Operation(OperationId("last"), plantId, date.plusSeconds(60), care)
      val operations = Vector(first, middle, last)
      seedPlant(dataSource, id = plantId.value)

      val store  = resource.store
      val logged = operations.map(store.addOperation)
      execute(dataSource, "update operation set date = '2026-01-01T00:00:00BAD' where id = ?", middle.id.value)
      val actual = store.getOperationDateRange(plantId)

      assertEquals(logged, operations.map(operation => LogOperationResult.Logged(operation.id)))
      actual match
        case GetOperationDateRangeResult.ReadFailed(reason) =>
          assertEquals(reason.getMessage, "invalid stored operation date: 2026-01-01T00:00:00BAD")
        case other => fail(s"expected ReadFailed, got $other")

  test("should read bounded watering dates for every active plant"):
    Using.resource(storeResource): resource =>
      val dataSource      = resource.dataSource
      val store           = resource.store
      val firstPlantId    = PlantId("p1")
      val secondPlantId   = PlantId("p2")
      val archivedPlantId = PlantId("archived")
      seedPlant(dataSource, id = firstPlantId.value)
      seedPlant(dataSource, id = secondPlantId.value)
      seedPlant(dataSource, id = archivedPlantId.value)
      seedPlant(dataSource, id = "no-waterings")

      val firstWaterings = Vector.tabulate(22): index =>
        Operation(OperationId(f"first-$index%02d"), firstPlantId, date.plusSeconds(index.toLong), care)
      val secondWaterings = Vector.tabulate(3): index =>
        Operation(OperationId(f"second-$index%02d"), secondPlantId, date, care)
      val archivedWatering = Operation(OperationId("archived-watering"), archivedPlantId, date, care)
      val nonWatering      = Operation(OperationId("care-only"), firstPlantId, date.plusSeconds(30), care.copy(actions = Set(ActionType.Pruned)))
      val operations       = firstWaterings ++ secondWaterings :+ archivedWatering :+ nonWatering
      val logged           = operations.map(store.addOperation)
      val archived         = store.updatePlant(Plant(archivedPlantId, defaultPlantDetails.copy(status = PlantStatus.Archived)))
      val actual           = store.getAttentionSamples(size = 20)

      val firstWateringHistory  = WateringHistory.from(firstWaterings.reverse.take(20).map(_.date)).fold(message => fail(message), identity)
      val secondWateringHistory = WateringHistory.from(secondWaterings.reverse.map(_.date)).fold(message => fail(message), identity)
      val emptyWateringHistory  = WateringHistory.from(Vector.empty).fold(message => fail(message), identity)
      val expectedSamples       = Vector(
        PlantAttentionSample(firstPlantId, firstWateringHistory),
        PlantAttentionSample(secondPlantId, secondWateringHistory),
        PlantAttentionSample(PlantId("no-waterings"), emptyWateringHistory)
      )
      assertEquals(logged, operations.map(operation => LogOperationResult.Logged(operation.id)))
      assertEquals(archived, UpdatePlantResult.Updated)
      assertEquals(actual, GetAttentionSamplesResult.Read(expectedSamples))

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

  test("should exclude archived plants from the attention read"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      val store      = resource.store
      seedPlant(dataSource, id = "archived", status = PlantStatus.Archived)
      execute(dataSource, "update plant set substrate = '[]' where id = ?", "archived")

      assertEquals(store.getAttentionSamples(size = 20), GetAttentionSamplesResult.Read(Vector.empty))

  test("should initialize the complete journal schema across all migrations"):
    Using.resource(Sqlite.make.connect(SqliteLocation.InMemory(UUID.randomUUID().toString))): connection =>
      val migration = Flyway.configure().dataSource(connection.dataSource).load()
      val _         = migration.migrate()
      seedPlant(connection.dataSource, id = "p1")

      val details = OperationDetails.Care(Set.empty, Set.empty, MoistureLevel.Wet, none)
      val older   = Operation(OperationId("o1"), PlantId("p1"), date, details)
      val newer   = Operation(OperationId("o2"), PlantId("p1"), date.plusMillis(100), details)
      val store   = SqlitePlantJournalStore.make(connection.transactor)
      assertEquals(
        migration.info().applied().toVector.map(_.getVersion),
        Vector(
          MigrationVersion.fromVersion("1"),
          MigrationVersion.fromVersion("2"),
          MigrationVersion.fromVersion("3"),
          MigrationVersion.fromVersion("4")
        )
      )
      assertEquals(store.addOperation(older), LogOperationResult.Logged(older.id))
      assertEquals(store.addOperation(newer), LogOperationResult.Logged(newer.id))
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
      seedPlant(dataSource, id = "p1")
      val updatedSubstrate = lecaSubstrate
      val updatedNickname  = Nickname("Monty").some
      val updatedDetails   =
        PlantDetails(Species("Monstera deliciosa"), updatedNickname, Location("Living room"), updatedSubstrate, PlantStatus.Active)
      val updatedPlant = Plant(PlantId("p1"), updatedDetails)

      val store      = resource.store
      val updated    = store.updatePlant(updatedPlant)
      val stored     = store.getPlant(updatedPlant.id)
      val activeList = store.getPlants(PlantStatus.Active)

      assertEquals(updated, UpdatePlantResult.Updated)
      assertEquals(stored, GetPlantResult.Read(updatedPlant))
      assertEquals(activeList, GetPlantsResult.Read(Vector(updatedPlant)))

  test("should preserve archived status when editing details and reject stale active updates"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      seedPlant(dataSource, id = "p1", status = PlantStatus.Archived)
      val activePlant   = Plant(PlantId("p1"), defaultPlantDetails)
      val archivedPlant = activePlant.copy(details = activePlant.details.copy(substrate = lecaSubstrate, status = PlantStatus.Archived))

      val store          = resource.store
      val rejectedUpdate = store.updatePlant(activePlant)
      val updated        = store.updatePlant(archivedPlant)
      val stored         = store.getPlant(archivedPlant.id)

      rejectedUpdate match
        case UpdatePlantResult.UpdateFailed(reason) => assertEquals(reason.getMessage, "plant not found while updating: p1")
        case other                                  => fail(s"expected UpdateFailed, got $other")
      assertEquals(updated, UpdatePlantResult.Updated)
      assertEquals(stored, GetPlantResult.Read(archivedPlant))

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

  test("should reject logging an operation for an unknown plant"):
    Using.resource(storeResource): resource =>
      val store   = resource.store
      val missing = Operation(OperationId("o1"), PlantId("no-such-plant"), date, care)

      val result = store.addOperation(missing)

      assertEquals(result, LogOperationResult.PlantMissing)

  test("should report a logging failure when the write is rejected"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      val store      = resource.store
      seedPlant(dataSource, id = "p1")
      val operation = Operation(OperationId("o1"), PlantId("p1"), date, care)

      val firstWrite     = store.addOperation(operation)
      val duplicateWrite = store.addOperation(operation)

      assertEquals(firstWrite, LogOperationResult.Logged(operation.id))
      duplicateWrite match
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

  test("should report write failures when the database is read-only"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      val store      = resource.store
      seedPlant(dataSource, id = "p1")
      val operation = Operation(OperationId("o1"), PlantId("p1"), date, care)
      assertEquals(store.addOperation(operation), LogOperationResult.Logged(operation.id))
      val readOnlyStore = SqlitePlantJournalStore.make(Transactor(dataSource, connectionConfig = makeReadOnly))

      readOnlyStore.updateOperation(operation.id, care) match
        case EditOperationResult.EditFailed(_) => ()
        case other                             => fail(s"expected EditFailed, got $other")
      List(readOnlyStore.removeOperation(operation.id), readOnlyStore.restoreOperation(operation)).foreach:
        case OperationCompensationResult.CompensationFailed(_) => ()
        case other                                             => fail(s"expected CompensationFailed, got $other")
      readOnlyStore.updatePlant(Plant(PlantId("p1"), defaultPlantDetails)) match
        case UpdatePlantResult.UpdateFailed(_) => ()
        case other                             => fail(s"expected UpdateFailed, got $other")

  test("should return read failures when the journal schema is unavailable"):
    Using.resource(Sqlite.make.connect(SqliteLocation.InMemory(UUID.randomUUID().toString))): connection =>
      val store = SqlitePlantJournalStore.make(connection.transactor)
      store.addPlant(Plant(PlantId("new"), defaultPlantDetails)) match
        case AddPlantResult.AddFailed(_) => ()
        case other                       => fail(s"expected AddFailed, got $other")
      store.getOperations(PlantId("p1"), fullWindow) match
        case GetOperationsResult.ReadFailed(_) => ()
        case other                             => fail(s"expected ReadFailed, got $other")
      store.getPlant(PlantId("p1")) match
        case GetPlantResult.ReadFailed(_) => ()
        case other                        => fail(s"expected ReadFailed, got $other")
      store.getPlants(PlantStatus.Active) match
        case GetPlantsResult.ReadFailed(_) => ()
        case other                         => fail(s"expected ReadFailed, got $other")
      store.getOperation(OperationId("o1")) match
        case GetOperationResult.ReadFailed(_) => ()
        case other                            => fail(s"expected ReadFailed, got $other")
      store.getAttentionSamples(size = 20) match
        case GetAttentionSamplesResult.ReadFailed(_) => ()
        case other                                   => fail(s"expected ReadFailed, got $other")

  test("should report archived count, date-range, and plant update errors when the schema is unavailable"):
    Using.resource(Sqlite.make.connect(SqliteLocation.InMemory(UUID.randomUUID().toString))): connection =>
      val store       = SqlitePlantJournalStore.make(connection.transactor)
      val countResult = store.getArchivedCount
      val dateResult  = store.getOperationDateRange(PlantId("p1"))
      val update      = store.updatePlant(Plant(PlantId("p1"), defaultPlantDetails.copy(status = PlantStatus.Archived)))

      countResult match
        case ArchivedCountResult.ReadFailed(_) => ()
        case other                             => fail(s"expected ReadFailed, got $other")
      dateResult match
        case GetOperationDateRangeResult.ReadFailed(_) => ()
        case other                                     => fail(s"expected ReadFailed, got $other")
      update match
        case UpdatePlantResult.UpdateFailed(_) => ()
        case other                             => fail(s"expected UpdateFailed, got $other")

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
    SqlitePlantJournalStore.make(connection.transactor)

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
