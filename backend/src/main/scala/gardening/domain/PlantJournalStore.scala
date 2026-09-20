package gardening.domain

trait PlantJournalStore:
  def getPlant(id: PlantId): GetPlantResult
  def getPlants: GetPlantsResult
  def getOperations(plantId: PlantId): GetOperationsResult
  def getOperation(id: OperationId): GetOperationResult
  def addOperation(operation: Operation): LogOperationResult
  def updateOperation(id: OperationId, details: OperationDetails): EditOperationResult
  def removeOperation(id: OperationId): OperationCompensationResult
  def restoreOperation(operation: Operation): OperationCompensationResult
  def updatePlant(plant: Plant): UpdatePlantResult
