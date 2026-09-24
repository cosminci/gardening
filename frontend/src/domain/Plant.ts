import type * as Journal from "./Journal";

export interface PlantClient {
  getPlants(status?: Journal.PlantStatus): Promise<Journal.GetPlantsResult>;
  getArchivedCount(): Promise<Journal.GetArchivedCountResult>;
  archivePlant(plantId: Journal.PlantId): Promise<Journal.ArchivePlantResult>;
}
