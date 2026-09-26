package gardening.ports

import gardening.domain.*
import gardening.domain.operations.*

trait OperationStore:
  def getOperations(plant: PlantId, window: OperationWindow): GetOperationsResult
  def getOperationDateRange(plant: PlantId): GetOperationDateRangeResult
  def getOperation(operation: OperationId): GetOperationResult
  def addOperation(operation: Operation): LogOperationResult
  def updateOperation(operation: OperationId, details: OperationDetails): EditOperationResult
  def removeOperation(operation: OperationId): OperationCompensationResult
  def restoreOperation(operation: Operation): OperationCompensationResult
