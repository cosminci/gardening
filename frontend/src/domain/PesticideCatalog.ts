import type * as Journal from "./Journal";

export interface PesticideClient {
  getPesticides(): Promise<Journal.CatalogReadResult<Journal.Pesticide>>;
  addPesticide(data: Journal.PesticideData): Promise<Journal.CatalogAddResult<Journal.Pesticide>>;
  editPesticide(
    id: Journal.PesticideId,
    data: Journal.PesticideData,
  ): Promise<Journal.CatalogEditResult<Journal.Pesticide>>;
}
