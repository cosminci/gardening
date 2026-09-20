package gardening.domain

trait PlantJournalStore:
  def getPlant(id: PlantId): Either[JournalReadFailure, Plant]
  def getPlants: Either[JournalReadFailure, Vector[Plant]]
  def getOperations(plantId: PlantId): Either[JournalReadFailure, Vector[Operation]]
  def getOperation(id: OperationId): Either[JournalReadFailure, Operation]
  def addOperation(operation: Operation): LogOperationResult
  def updateOperation(id: OperationId, details: OperationDetails): EditOperationResult
  def removeOperation(id: OperationId): Either[Throwable, Unit]
  def restoreOperation(operation: Operation): Either[Throwable, Unit]
  def updatePlant(plant: Plant): Either[Throwable, Unit]
