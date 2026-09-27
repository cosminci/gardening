package gardening.ports

import gardening.domain.*
import gardening.domain.plants.*

trait PlantStore:
  def addPlant(plant: Plant): AddPlantResult
  def getPlants(status: PlantStatus): GetPlantsResult
  def getArchivedCount: ArchivedCountResult
  def getPlant(plant: PlantId): GetPlantResult
  def updatePlant(plant: Plant): UpdatePlantResult
  def addPhoto(photo: PlantPhoto): AddPhotoResult
  def removePhoto(photo: PhotoId): RemovePhotoResult
  def getPhotos(plant: PlantId, window: PhotoWindow): GetPhotosResult
