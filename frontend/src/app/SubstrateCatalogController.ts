import type { Setter } from "solid-js";
import type * as Journal from "../domain/Journal";
import type { SubstrateClient } from "../domain/SubstrateCatalog";
import { appendEntry, replaceEntry } from "./CatalogSync";

export interface SubstrateCatalogController {
  readonly addSubstrateComponent: (
    data: Journal.SubstrateComponentData,
  ) => Promise<Journal.CatalogAddResult<Journal.SubstrateComponent>>;
  readonly editSubstrateComponent: SubstrateClient["editSubstrateComponent"];
  readonly archiveSubstrateComponent: SubstrateClient["archiveSubstrateComponent"];
  readonly addSubstrateMix: (
    name: Journal.SubstrateMixName,
    maybeNotes: Journal.SubstrateMixNotes | null,
    substrate: Journal.Substrate,
  ) => Promise<Journal.AddSubstrateMixResult>;
  readonly deleteSubstrateMix: (mix: Journal.SubstrateMix) => Promise<string | undefined>;
}

export const createSubstrateCatalogController = (
  getClient: () => SubstrateClient,
  setSubstrateComponents: Setter<readonly Journal.SubstrateComponent[]>,
  setSubstrateMixes: Setter<readonly Journal.SubstrateMix[]>,
): SubstrateCatalogController => ({
  addSubstrateComponent: async (data) => {
    const result = await getClient().addSubstrateComponent(data);
    if (result.kind === "added") appendEntry(setSubstrateComponents, result.entry);
    return result;
  },

  editSubstrateComponent: async (id, data) => {
    const result = await getClient().editSubstrateComponent(id, data);
    if (result.kind === "edited")
      replaceEntry(setSubstrateComponents, id, (component) => component.id, result.entry);
    return result;
  },

  archiveSubstrateComponent: async (id) => {
    const result = await getClient().archiveSubstrateComponent(id);
    if (result.kind === "archived")
      replaceEntry(setSubstrateComponents, id, (component) => component.id, result.entry);
    return result;
  },

  addSubstrateMix: async (name, maybeNotes, substrate) => {
    const result = await getClient().addSubstrateMix(name, maybeNotes, substrate);
    if (result.kind === "added") appendEntry(setSubstrateMixes, result.entry);
    return result;
  },

  deleteSubstrateMix: async (mix) => {
    let result: Journal.CatalogDeleteResult;
    try {
      result = await getClient().deleteSubstrateMix(mix.id);
    } catch {
      return "The mix could not be deleted.";
    }
    if (result.kind === "deleteFailed") return "The mix could not be deleted.";
    setSubstrateMixes((current) => current.filter((entry) => entry.id !== mix.id));
    return undefined;
  },
});
