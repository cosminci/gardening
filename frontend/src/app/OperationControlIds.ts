import type { OperationId, PlantId } from "../domain/Journal";

export const logOperationControlId = (id: PlantId) => `log-operation-${id}`;
export const editOperationControlId = (id: OperationId) => `edit-operation-${id}`;
