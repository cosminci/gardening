package gardening.domain

trait PlantJournalStore:
  def getPlant(id: PlantId): JournalReadResult[Plant]
  def getPlants: JournalReadResult[Vector[Plant]]
  def getOperations(plantId: PlantId): JournalReadResult[Vector[Operation]]
  def getOperation(id: OperationId): JournalReadResult[Operation]
  def addOperation(operation: Operation): LogOperationResult
  def updateOperation(id: OperationId, details: OperationDetails): EditOperationResult
  def updatePlant(plant: Plant): Unit
