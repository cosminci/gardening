import type { OperationId, PesticideId, PlantId, SubstrateComponentId } from "../domain/Journal";

export const logOperationControlId = (id: PlantId) => `log-operation-${id}`;
export const editOperationControlId = (id: OperationId) => `edit-operation-${id}`;
export const addPesticideControlId = "add-pesticide";
export const editPesticideControlId = (id: PesticideId) => `edit-pesticide-${id}`;
export const addSubstrateComponentControlId = "add-substrate-component";
export const editSubstrateComponentControlId = (index: number, id: SubstrateComponentId) =>
  `edit-substrate-component-${String(index)}-${id}`;
