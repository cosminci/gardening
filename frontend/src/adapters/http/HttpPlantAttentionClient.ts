import type { components, paths } from "@contract";
import createClient from "openapi-fetch";
import * as Journal from "../../domain/Journal";
import type { PlantAttentionClient } from "../../domain/PlantAttention";

type Wire = components["schemas"];

export const makeHttpPlantAttentionClient = (
  fetch: (request: Request) => Promise<Response> = globalThis.fetch,
): PlantAttentionClient => {
  const client = createClient<paths>({ baseUrl: globalThis.location.origin, fetch });

  return {
    async getAttention(): Promise<Journal.GetAttentionResult> {
      try {
        const { data, error } = await client.GET("/attention");
        return data === undefined
          ? { kind: "readFailed", reason: requestFailure(error) }
          : { kind: "read", projection: toAttentionProjection(data) };
      } catch (error) {
        return { kind: "readFailed", reason: requestFailure(error) };
      }
    },
  };
};

const toAttentionProjection = (value: Wire["AttentionProjection"]): Journal.AttentionProjection => {
  return {
    measuredAt: Journal.instant(value.measuredAt),
    plants: value.plants.map(toPlantAttention),
  };
};

const toPlantAttention = (value: Wire["PlantAttention"]): Journal.AttentionSample => {
  const watering = value.watering;
  switch (watering.kind) {
    case "unavailable":
      return {
        plantId: Journal.plantId(value.plantId),
        watering: {
          kind: "unavailable",
          sampleCount: watering.sampleCount,
          maybeElapsed:
            watering.elapsedMillis === null ? null : Journal.milliseconds(watering.elapsedMillis),
        },
      };
    case "current":
    case "overdue":
    case "redAlert":
      return {
        plantId: Journal.plantId(value.plantId),
        watering: {
          kind: watering.kind,
          sampleCount: watering.sampleCount,
          averageInterval: Journal.milliseconds(watering.averageIntervalMillis),
          elapsed: Journal.milliseconds(watering.elapsedMillis),
        },
      };
    default:
      throw new Error(`invalid watering attention: ${JSON.stringify(watering)}`);
  }
};

const requestFailure = (error: unknown): Error =>
  error instanceof Error ? error : new Error("journal request failed");
