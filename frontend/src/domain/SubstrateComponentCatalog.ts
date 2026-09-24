import type * as Journal from "./Journal";

export interface SubstrateComponentClient {
  getSubstrateComponents(): Promise<Journal.CatalogReadResult<Journal.SubstrateComponent>>;
  addSubstrateComponent(
    data: Journal.SubstrateComponentData,
  ): Promise<Journal.CatalogAddResult<Journal.SubstrateComponent>>;
  editSubstrateComponent(
    id: Journal.SubstrateComponentId,
    data: Journal.SubstrateComponentData,
  ): Promise<Journal.CatalogEditResult<Journal.SubstrateComponent>>;
}
