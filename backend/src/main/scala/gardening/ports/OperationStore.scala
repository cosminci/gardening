package gardening.ports

import gardening.domain.*
import gardening.domain.operations.*

import java.time.Instant

trait OperationStore:
  def getOperations(plant: PlantId, window: OperationWindow): GetOperationsResult
  def getOperationDateRange(plant: PlantId): GetOperationDateRangeResult
  def getLatestRepot(plant: PlantId): GetLatestRepotResult
  def getOperation(operation: OperationId): GetOperationResult
  def addOperation(operation: Operation): AddOperationResult
  def logRepot(id: OperationId, plant: PlantId, date: Instant, details: OperationDetails.Repot): AddOperationResult
  def updateOperation(operation: OperationId, details: OperationDetails): EditOperationResult
  def editRepot(operation: OperationId, details: OperationDetails.Repot): EditOperationResult
  def removeOperation(operation: OperationId): OperationCompensationResult
