import type { paths } from "@contract";
import createClient from "openapi-fetch";
import * as Journal from "../../domain/Journal";
import type { PlantPhotoClient } from "../../domain/PlantPhoto";

export type PhotoVariant = "original" | "thumbnail";

export const photoContentUrl = (id: Journal.PhotoId, variant: PhotoVariant): string =>
  `/photos/${String(id)}/content?variant=${variant}`;

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
        formData.append("idempotencyKey", randomIdempotencyKey());
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

// crypto.randomUUID() is restricted to secure contexts (HTTPS or localhost); this app is also used
// over plain HTTP on the local network, so the idempotency key is built from getRandomValues, which
// carries no such restriction, instead.
const randomIdempotencyKey = (): string => {
  const bytes = Array.from(crypto.getRandomValues(new Uint8Array(16)), (byte, index) => {
    if (index === 6) return (byte & 0x0f) | 0x40;
    if (index === 8) return (byte & 0x3f) | 0x80;
    return byte;
  });
  const hex = bytes.map((byte) => byte.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
};
