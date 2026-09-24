import type { components, paths } from "@contract";
import createClient from "openapi-fetch";
import * as Journal from "../../domain/Journal";
import type { PlantClient } from "../../domain/Plant";
import { fromWireSubstrate, toWireSubstrate } from "./Codecs";

type Wire = components["schemas"];

export const makeHttpPlantClient = (
  fetch: (request: Request) => Promise<Response> = globalThis.fetch,
): PlantClient => {
  const client = createClient<paths>({ baseUrl: globalThis.location.origin, fetch });

  return {
    async createPlant(details): Promise<Journal.CreatePlantResult> {
      try {
        const { data, error, response } = await client.POST("/plants", {
          body: {
            species: details.species,
            nickname: details.maybeNickname,
            location: details.location,
            substrate: toWireSubstrate(details.substrate),
          },
        });
        if (response.status === 201 && data !== undefined)
          return { kind: "created", plant: toPlant(data) };
        if (response.status === 422) return { kind: "unknownComponent" };
        if (response.status === 503)
          return { kind: "catalogReadFailed", reason: requestFailure(error) };
        return { kind: "createFailed", reason: requestFailure(error) };
      } catch (error) {
        return { kind: "createFailed", reason: requestFailure(error) };
      }
    },

    async getPlants(status): Promise<Journal.GetPlantsResult> {
      try {
        const { data, error } = await client.GET("/plants", {
          params: { query: status === undefined ? {} : { status } },
        });
        return data === undefined
          ? { kind: "readFailed", reason: requestFailure(error) }
          : { kind: "read", plants: data.map(toPlant) };
      } catch (error) {
        return { kind: "readFailed", reason: requestFailure(error) };
      }
    },

    async getArchivedCount(): Promise<Journal.GetArchivedCountResult> {
      try {
        const { data, error } = await client.GET("/plants/archived/count");
        if (data === undefined) return { kind: "readFailed", reason: requestFailure(error) };
        if (!Number.isSafeInteger(data.count) || data.count < 0)
          return { kind: "readFailed", reason: new Error("invalid archived plant count") };
        return { kind: "read", count: data.count };
      } catch (error) {
        return { kind: "readFailed", reason: requestFailure(error) };
      }
    },

    async archivePlant(id): Promise<Journal.ArchivePlantResult> {
      try {
        const { error, response } = await client.PATCH("/plants/{plantId}", {
          params: { path: { plantId: id } },
          headers: { "Content-Type": "application/json-patch+json" },
          body: [{ op: "replace", path: "/details/status", value: "archived" }],
        });
        if (response.status === 204) return { kind: "archived" };
        if (response.status === 404) return { kind: "plantMissing" };
        if (response.status === 409) return { kind: "alreadyArchived" };
        return { kind: "archiveFailed", reason: requestFailure(error) };
      } catch (error) {
        return { kind: "archiveFailed", reason: requestFailure(error) };
      }
    },

    async editPlant(plantId, details): Promise<Journal.EditPlantResult> {
      try {
        const { error, response } = await client.PATCH("/plants/{plantId}", {
          params: { path: { plantId } },
          headers: { "Content-Type": "application/json-patch+json" },
          body: [
            {
              op: "replace",
              path: "/details",
              value: {
                species: details.species,
                nickname: details.maybeNickname,
                location: details.location,
                substrate: toWireSubstrate(details.substrate),
              },
            },
          ],
        });
        if (response.status === 204) return { kind: "edited" };
        if (response.status === 404) return { kind: "plantMissing" };
        if (response.status === 409) return { kind: "plantArchived" };
        if (response.status === 422) return { kind: "unknownComponent" };
        if (response.status === 503)
          return { kind: "catalogReadFailed", reason: requestFailure(error) };
        return { kind: "editFailed", reason: requestFailure(error) };
      } catch (error) {
        return { kind: "editFailed", reason: requestFailure(error) };
      }
    },
  };
};

const toPlant = (value: Wire["Plant"]): Journal.Plant => ({
  id: Journal.plantId(value.id),
  details: {
    species: Journal.species(value.details.species),
    maybeNickname:
      value.details.nickname === null ? null : Journal.nickname(value.details.nickname),
    location: Journal.location(value.details.location),
    substrate: fromWireSubstrate(value.details.substrate),
    status: value.details.status,
  },
});

const requestFailure = (error: unknown): Error =>
  error instanceof Error ? error : new Error("plant request failed");
