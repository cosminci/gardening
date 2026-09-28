package gardening.ports

import gardening.domain.*
import gardening.domain.plants.*

trait PhotoStore:
  def addPhoto(photo: PlantPhoto): AddPhotoResult
  def removePhoto(photo: PhotoId): RemovePhotoResult
  def getPhotos(plant: PlantId, window: PhotoWindow): GetPhotosResult
