import type { paths } from "@contract";
import createClient from "openapi-fetch";
import * as Journal from "../../domain/Journal";
import type { PlantPhotoClient } from "../../domain/PlantPhoto";

export const photoContentUrl = (id: Journal.PhotoId): string => `/photos/${String(id)}/content`;

export const makeHttpPlantPhotoClient = (
  fetch: (request: Request) => Promise<Response> = globalThis.fetch,
): PlantPhotoClient => {
  const client = createClient<paths>({ baseUrl: globalThis.location.origin, fetch });

  return {
    async getPhotos(plant, window): Promise<Journal.GetPhotosResult> {
      try {
        const { data, error } = await client.GET("/plants/{plantId}/photos", {
          params: {
            path: { plantId: plant },
            query: { offset: window.offset, pageSize: window.size },
          },
        });
        return data === undefined
          ? { kind: "readFailed", reason: requestFailure(error) }
          : {
              kind: "read",
              page: {
                photos: data.photos.map(toPlantPhoto),
                hasNextPage: data.hasNextPage,
              },
            };
      } catch (error) {
        return { kind: "readFailed", reason: requestFailure(error) };
      }
    },

    async addPhoto(plant, file): Promise<Journal.AddPhotoResult> {
      try {
        const formData = new FormData();
        formData.append("file", file);
        const { data, error, response } = await client.POST("/plants/{plantId}/photos", {
          params: { path: { plantId: plant } },
          body: formData as never,
          bodySerializer: (body) => body as never,
        });
        if (data !== undefined) return { kind: "added", photo: toPlantPhoto(data) };
        if (response.status === 404) return { kind: "plantMissing" };
        if (response.status === 413) return { kind: "tooLarge" };
        if (response.status === 415) return { kind: "unsupportedMediaType" };
        return { kind: "addFailed", reason: requestFailure(error) };
      } catch (error) {
        return { kind: "addFailed", reason: requestFailure(error) };
      }
    },

    async removePhoto(photo): Promise<Journal.RemovePhotoResult> {
      try {
        const { error, response } = await client.DELETE("/photos/{photoId}", {
          params: { path: { photoId: photo } },
        });
        if (response.status === 204) return { kind: "removed" };
        if (response.status === 404) return { kind: "photoMissing" };
        return { kind: "removeFailed", reason: requestFailure(error) };
      } catch (error) {
        return { kind: "removeFailed", reason: requestFailure(error) };
      }
    },
  };
};

const toPlantPhoto = (value: { id: string; capturedAt: string }): Journal.PlantPhoto => ({
  id: Journal.photoId(value.id),
  capturedAt: Journal.instant(value.capturedAt),
});

const requestFailure = (error: unknown): Error =>
  error instanceof Error ? error : new Error("photo request failed");
