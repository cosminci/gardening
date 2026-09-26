import type * as Journal from "./Journal";

export interface OperationClient {
  getOperationDates(plant: Journal.PlantId): Promise<Journal.GetOperationDatesResult>;
  getOperations(
    plant: Journal.PlantId,
    window: Journal.OperationWindow,
  ): Promise<Journal.GetOperationsResult>;
  logOperation(
    plant: Journal.PlantId,
    date: Journal.Instant,
    details: Journal.OperationDetails,
  ): Promise<Journal.LogOperationResult>;
  editOperation(
    operation: Journal.OperationId,
    details: Journal.OperationDetails,
  ): Promise<Journal.EditOperationResult>;
  deleteOperation(operation: Journal.OperationId): Promise<Journal.DeleteOperationResult>;
}
