import type * as Journal from "./Journal";

export interface SubstrateClient {
  getSubstrateComponents(): Promise<Journal.CatalogReadResult<Journal.SubstrateComponent>>;
  addSubstrateComponent(
    data: Journal.SubstrateComponentData,
  ): Promise<Journal.CatalogAddResult<Journal.SubstrateComponent>>;
  editSubstrateComponent(
    id: Journal.SubstrateComponentId,
    data: Journal.SubstrateComponentData,
  ): Promise<Journal.SubstrateComponentEditResult>;
  archiveSubstrateComponent(
    id: Journal.SubstrateComponentId,
  ): Promise<Journal.SubstrateComponentArchiveResult>;
  getSubstrateMixes(): Promise<Journal.CatalogReadResult<Journal.SubstrateMix>>;
  addSubstrateMix(
    name: Journal.SubstrateMixName,
    maybeNotes: Journal.SubstrateMixNotes | null,
    substrate: Journal.Substrate,
  ): Promise<Journal.AddSubstrateMixResult>;
  deleteSubstrateMix(id: Journal.SubstrateMixId): Promise<Journal.CatalogDeleteResult>;
}
