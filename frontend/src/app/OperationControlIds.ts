import type * as Journal from "../domain/Journal";

export const logOperationControlId = (id: Journal.PlantId) => `log-operation-${id}`;
export const editOperationControlId = (id: Journal.OperationId) => `edit-operation-${id}`;
export const deleteOperationControlId = (id: Journal.OperationId) => `delete-operation-${id}`;
export const addPesticideControlId = "add-pesticide";
export const editPesticideControlId = (id: Journal.PesticideId) => `edit-pesticide-${id}`;
export const archivePesticideControlId = (id: Journal.PesticideId) => `archive-pesticide-${id}`;
export const addSubstrateComponentControlId = "add-substrate-component";
export const editSubstrateComponentControlId = (index: number, id: Journal.SubstrateComponentId) =>
  `edit-substrate-component-${String(index)}-${id}`;
export const saveSubstrateMixControlId = "save-substrate-mix";
export const loadSubstrateMixControlId = "load-substrate-mix";
export const deleteSubstrateMixControlId = (id: Journal.SubstrateMixId) =>
  `delete-substrate-mix-${id}`;
