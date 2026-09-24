import type * as Journal from "./Journal";

export interface OperationClient {
  getOperationDates(plantId: Journal.PlantId): Promise<Journal.GetOperationDatesResult>;
  getOperations(
    plantId: Journal.PlantId,
    window: Journal.OperationWindow,
  ): Promise<Journal.GetOperationsResult>;
  logOperation(
    plantId: Journal.PlantId,
    date: Journal.Instant,
    details: Journal.OperationDetails,
  ): Promise<Journal.LogOperationResult>;
  editOperation(
    operationId: Journal.OperationId,
    details: Journal.OperationDetails,
  ): Promise<Journal.EditOperationResult>;
}
