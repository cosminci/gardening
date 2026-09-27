package gardening.ports

import gardening.domain.*
import gardening.domain.operations.*

trait OperationStore:
  def getOperations(plant: PlantId, window: OperationWindow): GetOperationsResult
  def getOperationDateRange(plant: PlantId): GetOperationDateRangeResult
  def getLatestRepot(plant: PlantId): GetLatestRepotResult
  def getOperation(operation: OperationId): GetOperationResult
  def addOperation(operation: Operation): AddOperationResult
  def updateOperation(operation: OperationId, details: OperationDetails): EditOperationResult
  def removeOperation(operation: OperationId): OperationCompensationResult
  def restoreOperation(operation: Operation): OperationCompensationResult
