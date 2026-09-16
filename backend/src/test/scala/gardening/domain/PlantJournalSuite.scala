package gardening.domain

import io.github.iltotore.iron.*

import java.time.Instant

class PlantJournalSuite extends munit.FunSuite:

  private val substrate: Substrate =
    Substrate
      .of(List(SubstratePart(SubstrateComponent.Perlite, share = 100)))
      .getOrElse(fail("invalid test substrate"))

  private val plant: Plant =
    Plant(
      id = PlantId("p1"),
      species = Species("Ficus lyrata"),
      maybeNickname = Some(Nickname("Fern")),
      location = Location("Balcony"),
      substrate = substrate,
      status = PlantStatus.Active
    )

  private val details: OperationDetails =
    OperationDetails(
      date = Instant.parse("2026-01-01T00:00:00Z"),
      actions = Set(ActionType.Watered),
      moisture = MoistureLevel.Wet,
      maybeSubstrate = None,
      maybeNote = Some(Note("dry"))
    )

  private val operation: Operation = Operation(OperationId("o1"), PlantId("p1"), details)

  private def buildJournal(
      plants: Vector[Plant] = Vector.empty,
      operations: Vector[Operation] = Vector.empty,
      logResult: LogOperationResult = LogOperationResult.Logged(OperationId("id-1")),
      editResult: EditOperationResult = EditOperationResult.OperationMissing,
      removeResult: RemoveOperationResult = RemoveOperationResult.AlreadyRemoved
  ): PlantJournal =
    val store = new PlantJournalStore:
      def getPlants: Vector[Plant]                                                         = plants
      def getOperations(plantId: PlantId): Vector[Operation]                               = operations
      def addOperation(operation: Operation): LogOperationResult                           = logResult
      def updateOperation(id: OperationId, details: OperationDetails): EditOperationResult = editResult
      def deleteOperation(id: OperationId): RemoveOperationResult                          = removeResult
    given IdGenerator = () => "id-1"
    PlantJournal.make(store)

  test("should list the plants the store holds"):
    assertEquals(buildJournal(plants = Vector(plant)).getPlants, Vector(plant))

  test("should return the operations recorded for a plant"):
    assertEquals(buildJournal(operations = Vector(operation)).getOperations(PlantId("p1")), Vector(operation))

  test("should report the operation it logged"):
    assertEquals(
      buildJournal(logResult = LogOperationResult.Logged(OperationId("id-1"))).logOperation(PlantId("p1"), details),
      LogOperationResult.Logged(OperationId("id-1"))
    )

  test("should surface a logging failure from the store"):
    val cause = RuntimeException("store down")
    assertEquals(
      buildJournal(logResult = LogOperationResult.LoggingFailed(cause)).logOperation(PlantId("p1"), details),
      LogOperationResult.LoggingFailed(cause)
    )

  test("should report the operation it edited"):
    assertEquals(
      buildJournal(editResult = EditOperationResult.Edited(operation)).editOperation(OperationId("o1"), details),
      EditOperationResult.Edited(operation)
    )

  test("should report a missing operation when editing an unknown id"):
    assertEquals(
      buildJournal(editResult = EditOperationResult.OperationMissing).editOperation(OperationId("nope"), details),
      EditOperationResult.OperationMissing
    )

  test("should surface an edit failure from the store"):
    val cause = RuntimeException("store down")
    assertEquals(
      buildJournal(editResult = EditOperationResult.EditFailed(cause)).editOperation(OperationId("o1"), details),
      EditOperationResult.EditFailed(cause)
    )

  test("should report the operation it removed"):
    assertEquals(
      buildJournal(removeResult = RemoveOperationResult.Removed(operation)).removeOperation(OperationId("o1")),
      RemoveOperationResult.Removed(operation)
    )

  test("should report an already-removed operation"):
    assertEquals(
      buildJournal(removeResult = RemoveOperationResult.AlreadyRemoved).removeOperation(OperationId("nope")),
      RemoveOperationResult.AlreadyRemoved
    )

  test("should surface a removal failure from the store"):
    val cause = RuntimeException("store down")
    assertEquals(
      buildJournal(removeResult = RemoveOperationResult.RemoveFailed(cause)).removeOperation(OperationId("o1")),
      RemoveOperationResult.RemoveFailed(cause)
    )
