import type { components, paths } from "@contract";
import createClient from "openapi-fetch";
import * as Journal from "../../domain/Journal";

type Wire = components["schemas"];

export const makeHttpJournalClient = (
  fetch: (request: Request) => Promise<Response> = globalThis.fetch,
): Journal.JournalClient => {
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

    async getOperations(id, window): Promise<Journal.GetOperationsResult> {
      try {
        const { data, error } = await client.GET("/plants/{plantId}/operations", {
          params: {
            path: { plantId: id },
            query: { offset: window.offset, pageSize: window.size },
          },
        });
        return data === undefined
          ? { kind: "readFailed", reason: requestFailure(error) }
          : {
              kind: "read",
              page: {
                operations: data.operations.map(toOperation),
                hasNextPage: data.hasNextPage,
              },
            };
      } catch (error) {
        return { kind: "readFailed", reason: requestFailure(error) };
      }
    },

    async logOperation(id, details): Promise<Journal.LogOperationResult> {
      try {
        const { data, error } = await client.POST("/plants/{plantId}/operations", {
          params: { path: { plantId: id } },
          body: toWireDetails(details),
        });
        return data === undefined
          ? { kind: "loggingFailed", reason: requestFailure(error) }
          : { kind: "logged", id: Journal.operationId(data.id) };
      } catch (error) {
        return { kind: "loggingFailed", reason: requestFailure(error) };
      }
    },

    async editOperation(id, details): Promise<Journal.EditOperationResult> {
      try {
        const { data, error, response } = await client.PUT("/operations/{operationId}", {
          params: { path: { operationId: id } },
          body: toWireDetails(details),
        });
        if (data !== undefined) return { kind: "edited", operation: toOperation(data) };
        if (response.status === 404) return { kind: "operationMissing" };
        if (response.status === 409) return { kind: "operationTypeMismatch" };
        return { kind: "editFailed", reason: requestFailure(error) };
      } catch (error) {
        return { kind: "editFailed", reason: requestFailure(error) };
      }
    },

    async getSubstrateComponents(): Promise<Journal.CatalogReadResult<Journal.SubstrateComponent>> {
      try {
        const { data, error } = await client.GET("/substrate-components");
        return data === undefined
          ? { kind: "readFailed", reason: requestFailure(error) }
          : { kind: "read", entries: data.map(toSubstrateComponent) };
      } catch (error) {
        return { kind: "readFailed", reason: requestFailure(error) };
      }
    },

    async addSubstrateComponent(
      value: Journal.SubstrateComponentData,
    ): Promise<Journal.CatalogAddResult<Journal.SubstrateComponent>> {
      try {
        const { data, error } = await client.POST("/substrate-components", {
          body: toWireSubstrateComponentData(value),
        });
        return data === undefined
          ? { kind: "addFailed", reason: requestFailure(error) }
          : { kind: "added", entry: toSubstrateComponent(data) };
      } catch (error) {
        return { kind: "addFailed", reason: requestFailure(error) };
      }
    },

    async editSubstrateComponent(
      id,
      value,
    ): Promise<Journal.CatalogEditResult<Journal.SubstrateComponent>> {
      try {
        const { data, error, response } = await client.PUT("/substrate-components/{componentId}", {
          params: { path: { componentId: id } },
          body: toWireSubstrateComponentData(value),
        });
        if (data !== undefined) return { kind: "edited", entry: toSubstrateComponent(data) };
        if (response.status === 404) return { kind: "recordMissing" };
        return { kind: "editFailed", reason: requestFailure(error) };
      } catch (error) {
        return { kind: "editFailed", reason: requestFailure(error) };
      }
    },

    async getPesticides(): Promise<Journal.CatalogReadResult<Journal.Pesticide>> {
      try {
        const { data, error } = await client.GET("/pesticides");
        return data === undefined
          ? { kind: "readFailed", reason: requestFailure(error) }
          : { kind: "read", entries: data.map(toPesticide) };
      } catch (error) {
        return { kind: "readFailed", reason: requestFailure(error) };
      }
    },

    async addPesticide(
      value: Journal.PesticideData,
    ): Promise<Journal.CatalogAddResult<Journal.Pesticide>> {
      try {
        const { data, error } = await client.POST("/pesticides", {
          body: toWirePesticideData(value),
        });
        return data === undefined
          ? { kind: "addFailed", reason: requestFailure(error) }
          : { kind: "added", entry: toPesticide(data) };
      } catch (error) {
        return { kind: "addFailed", reason: requestFailure(error) };
      }
    },

    async editPesticide(id, value): Promise<Journal.CatalogEditResult<Journal.Pesticide>> {
      try {
        const { data, error, response } = await client.PUT("/pesticides/{pesticideId}", {
          params: { path: { pesticideId: id } },
          body: toWirePesticideData(value),
        });
        if (data !== undefined) return { kind: "edited", entry: toPesticide(data) };
        if (response.status === 404) return { kind: "recordMissing" };
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
    substrate: Journal.substrate(
      value.details.substrate.map((part) => ({
        component: Journal.substrateComponentId(part.componentId),
        share: Journal.percentage(part.share),
      })),
    ),
    status: value.details.status,
  },
});

const toAttentionProjection = (
  value: Wire["AttentionProjectionResponse"],
): Journal.AttentionProjection => {
  return {
    measuredAt: Journal.instant(value.measuredAt),
    plants: required(value.plants, "attention projection is missing plants").map(toPlantAttention),
  };
};

const toPlantAttention = (value: Wire["PlantAttentionResponse"]): Journal.PlantAttention => {
  const elapsed = value.elapsed ?? null;
  const maybeElapsed = elapsed === null ? null : Journal.duration(elapsed);
  if (!value.cadenceAvailable)
    return {
      plant: toPlant(value.plant),
      cadence: { kind: "unavailable", sampleCount: value.sampleCount, maybeElapsed },
    };

  return {
    plant: toPlant(value.plant),
    cadence: {
      kind: "inferred",
      sampleCount: value.sampleCount,
      averageInterval: Journal.duration(
        required(value.averageInterval, "inferred watering cadence is missing its average"),
      ),
      elapsed: required(maybeElapsed, "inferred watering cadence is missing elapsed time"),
      urgency: toUrgency(required(value.urgency, "inferred watering cadence is missing urgency")),
      state: toWateringState(required(value.state, "inferred watering cadence is missing state")),
    },
  };
};

const toUrgency = (value: Wire["UrgencyResponse"]): Journal.Urgency => {
  if (value.unbounded) return { kind: "unbounded" };
  return {
    kind: "finite",
    numeratorNanos: required(value.numeratorNanos, "finite urgency is missing its numerator"),
    denominatorNanos: required(value.denominatorNanos, "finite urgency is missing its denominator"),
  };
};

const toWateringState = (value: unknown): Journal.WateringState => {
  if (value === "current" || value === "overdue" || value === "redAlert") return value;
  throw new Error(`invalid watering state: ${String(value)}`);
};

const required = <Value>(value: Value | null | undefined, message: string): Value => {
  const present = value ?? null;
  if (present === null) throw new Error(message);
  return present;
};

const toOperation = (value: Wire["Operation"]): Journal.Operation => ({
  id: Journal.operationId(value.id),
  plantId: Journal.plantId(value.plantId),
  date: Journal.instant(value.date),
  details:
    value.details.kind === "care"
      ? {
          kind: "care",
          actions: new Set(value.details.actions),
          pesticides: new Set(value.details.pesticides.map(Journal.pesticideId)),
          moisture: value.details.moisture,
          maybeNote: value.details.notes === null ? null : Journal.note(value.details.notes),
        }
      : {
          kind: "repot",
          substrate: Journal.substrate(
            value.details.substrate.map((part) => ({
              component: Journal.substrateComponentId(part.componentId),
              share: Journal.percentage(part.share),
            })),
          ),
          maybeNote: value.details.notes === null ? null : Journal.note(value.details.notes),
        },
});

const toWireDetails = (details: Journal.OperationDetails): Wire["OperationDetails"] =>
  details.kind === "care"
    ? {
        kind: "care",
        actions: [...details.actions].sort(),
        pesticides: [...details.pesticides].sort(),
        moisture: details.moisture,
        notes: details.maybeNote,
      }
    : {
        kind: "repot",
        substrate: details.substrate.map((part) => ({
          componentId: part.component,
          share: part.share,
        })),
        notes: details.maybeNote,
      };

const toSubstrateComponent = (value: Wire["SubstrateComponent"]): Journal.SubstrateComponent => ({
  id: Journal.substrateComponentId(value.id),
  data: {
    name: Journal.nomenclatureName(value.data.name),
    maybeInfo: value.data.info === null ? null : Journal.nomenclatureInfo(value.data.info),
  },
});

const toWireSubstrateComponentData = (
  value: Journal.SubstrateComponentData,
): Wire["SubstrateComponentData"] => ({
  name: value.name,
  info: value.maybeInfo,
});

const toPesticide = (value: Wire["Pesticide"]): Journal.Pesticide => ({
  id: Journal.pesticideId(value.id),
  data: {
    name: Journal.nomenclatureName(value.data.name),
    pesticideType: value.data.type,
    maybeInfo: value.data.info === null ? null : Journal.nomenclatureInfo(value.data.info),
  },
});

const toWirePesticideData = (value: Journal.PesticideData): Wire["PesticideData"] => ({
  name: value.name,
  type: value.pesticideType,
  info: value.maybeInfo,
});

const requestFailure = (error: unknown): Error =>
  error instanceof Error ? error : new Error("journal request failed");
