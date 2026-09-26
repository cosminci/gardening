import type * as Journal from "./Journal";

export interface PlantPhotoClient {
  getPhotos(
    plantId: Journal.PlantId,
    window: Journal.PhotoWindow,
  ): Promise<Journal.GetPhotosResult>;
  addPhoto(plantId: Journal.PlantId, file: File): Promise<Journal.AddPhotoResult>;
  removePhoto(photoId: Journal.PhotoId): Promise<Journal.RemovePhotoResult>;
}
