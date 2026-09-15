package gardening.domain

trait PlantJournalStore:
  def getPlants: Vector[Plant]
  def getOperations(plantId: PlantId): Vector[Operation]
  def addOperation(operation: Operation): LogOperationResult
  def updateOperation(id: OperationId, details: OperationDetails): EditOperationResult
  def deleteOperation(id: OperationId): RemoveOperationResult
