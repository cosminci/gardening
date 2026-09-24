import type * as Journal from "./Journal";

export interface PlantClient {
  getPlants(status?: Journal.PlantStatus): Promise<Journal.GetPlantsResult>;
  createPlant(details: Journal.NewPlantDetails): Promise<Journal.CreatePlantResult>;
  getArchivedCount(): Promise<Journal.GetArchivedCountResult>;
  archivePlant(plantId: Journal.PlantId): Promise<Journal.ArchivePlantResult>;
}
