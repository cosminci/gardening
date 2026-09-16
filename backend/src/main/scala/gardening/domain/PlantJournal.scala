package gardening.domain

trait PlantJournal:
  def getPlants: Vector[Plant]
  def getOperations(plantId: PlantId): Vector[Operation]
  def logOperation(plantId: PlantId, op: OperationDetails): LogOperationResult
  def editOperation(id: OperationId, details: OperationDetails): EditOperationResult
  def removeOperation(id: OperationId): RemoveOperationResult

object PlantJournal:

  def make(store: PlantJournalStore)(using IdGenerator): PlantJournal =
    new LivePlantJournal(store)

  private class LivePlantJournal(store: PlantJournalStore)(using idGen: IdGenerator) extends PlantJournal:

    def getPlants: Vector[Plant] =
      store.getPlants

    def getOperations(plantId: PlantId): Vector[Operation] =
      store.getOperations(plantId)

    def logOperation(plantId: PlantId, op: OperationDetails): LogOperationResult =
      store.addOperation(Operation(OperationId(idGen.nextId()), plantId, op))

    def editOperation(id: OperationId, details: OperationDetails): EditOperationResult =
      store.updateOperation(id, details)

    def removeOperation(id: OperationId): RemoveOperationResult =
      store.deleteOperation(id)
