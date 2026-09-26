import type * as Journal from "./Journal";

export interface PlantPhotoClient {
  getPhotos(plant: Journal.PlantId, window: Journal.PhotoWindow): Promise<Journal.GetPhotosResult>;
  addPhoto(plant: Journal.PlantId, file: File): Promise<Journal.AddPhotoResult>;
  removePhoto(photo: Journal.PhotoId): Promise<Journal.RemovePhotoResult>;
}
