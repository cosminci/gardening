package gardening.domain.journal

import gardening.domain.*

trait PlantJournalStore:
  def getPlants(status: PlantStatus): GetPlantsResult
  def getArchivedCount: ArchivedCountResult
  def getPlant(id: PlantId): GetPlantResult
  def updatePlant(plant: Plant): UpdatePlantResult
  def archivePlant(id: PlantId): ArchivePlantResult
  def getOperations(plantId: PlantId, window: OperationWindow): GetOperationsResult
  def getOperationDateRange(plantId: PlantId): GetOperationDateRangeResult
  def getOperation(id: OperationId): GetOperationResult
  def addOperation(operation: Operation): LogOperationResult
  def updateOperation(id: OperationId, details: OperationDetails): EditOperationResult
  def removeOperation(id: OperationId): OperationCompensationResult
  def restoreOperation(operation: Operation): OperationCompensationResult
