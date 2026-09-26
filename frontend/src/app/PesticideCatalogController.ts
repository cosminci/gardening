import type { Setter } from "solid-js";
import type * as Journal from "../domain/Journal";
import type { PesticideClient } from "../domain/PesticideCatalog";
import { appendEntry, replaceEntry } from "./CatalogSync";

export interface PesticideCatalogController {
  readonly addPesticide: (
    data: Journal.PesticideData,
  ) => Promise<Journal.CatalogAddResult<Journal.Pesticide>>;
  readonly editPesticide: PesticideClient["editPesticide"];
  readonly archivePesticide: PesticideClient["archivePesticide"];
}

export const createPesticideCatalogController = (
  getClient: () => PesticideClient,
  setPesticides: Setter<readonly Journal.Pesticide[]>,
): PesticideCatalogController => ({
  addPesticide: async (data) => {
    const result = await getClient().addPesticide(data);
    if (result.kind === "added") appendEntry(setPesticides, result.entry);
    return result;
  },

  editPesticide: async (id, data) => {
    const result = await getClient().editPesticide(id, data);
    if (result.kind === "edited")
      replaceEntry(setPesticides, id, (pesticide) => pesticide.id, result.entry);
    return result;
  },

  archivePesticide: async (id) => {
    const result = await getClient().archivePesticide(id);
    if (result.kind === "archived")
      replaceEntry(setPesticides, id, (pesticide) => pesticide.id, result.entry);
    return result;
  },
});
