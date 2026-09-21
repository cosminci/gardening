import type { components, paths } from "@contract";
import createClient from "openapi-fetch";
import type {
  EditOperationResult,
  GetOperationsResult,
  GetPlantsResult,
  JournalClient,
  LogOperationResult,
  Operation,
  OperationDetails,
  Plant,
} from "../../domain/Journal";
import {
  instant,
  location,
  nickname,
  note,
  operationId,
  pesticideId,
  percentage,
  plantId,
  substrateComponentId,
  species,
  substrate,
} from "../../domain/Journal";

type Wire = components["schemas"];

export const makeHttpJournalClient = (
  fetch: (request: Request) => Promise<Response> = globalThis.fetch,
): JournalClient => {
  const client = createClient<paths>({ baseUrl: globalThis.location.origin, fetch });

  return {
    async getPlants(): Promise<GetPlantsResult> {
      try {
        const { data, error } = await client.GET("/plants");
        return data === undefined
          ? { kind: "readFailed", reason: requestFailure(error) }
          : { kind: "read", plants: data.map(toPlant) };
      } catch (error) {
        return { kind: "readFailed", reason: requestFailure(error) };
      }
    },

    async getOperations(id): Promise<GetOperationsResult> {
      try {
        const { data, error } = await client.GET("/plants/{plantId}/operations", {
          params: { path: { plantId: id } },
        });
        return data === undefined
          ? { kind: "readFailed", reason: requestFailure(error) }
          : { kind: "read", operations: data.map(toOperation) };
      } catch (error) {
        return { kind: "readFailed", reason: requestFailure(error) };
      }
    },

    async logOperation(id, details): Promise<LogOperationResult> {
      try {
        const { data, error } = await client.POST("/plants/{plantId}/operations", {
          params: { path: { plantId: id } },
          body: toWireDetails(details),
        });
        return data === undefined
          ? { kind: "loggingFailed", reason: requestFailure(error) }
          : { kind: "logged", id: operationId(data.id) };
      } catch (error) {
        return { kind: "loggingFailed", reason: requestFailure(error) };
      }
    },

    async editOperation(id, details): Promise<EditOperationResult> {
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
  };
};

const toPlant = (value: Wire["Plant"]): Plant => ({
  id: plantId(value.id),
  details: {
    species: species(value.details.species),
    maybeNickname: value.details.nickname === null ? null : nickname(value.details.nickname),
    location: location(value.details.location),
    substrate: substrate(
      value.details.substrate.map((part) => ({
        component: substrateComponentId(part.componentId),
        share: percentage(part.share),
      })),
    ),
    status: value.details.status,
  },
});

const toOperation = (value: Wire["Operation"]): Operation => ({
  id: operationId(value.id),
  plantId: plantId(value.plantId),
  date: instant(value.date),
  details:
    value.details.kind === "care"
      ? {
          kind: "care",
          actions: new Set(value.details.actions),
          pesticides: new Set(value.details.pesticides.map(pesticideId)),
          moisture: value.details.moisture,
          maybeNote: value.details.notes === null ? null : note(value.details.notes),
        }
      : {
          kind: "repot",
          substrate: substrate(
            value.details.substrate.map((part) => ({
              component: substrateComponentId(part.componentId),
              share: percentage(part.share),
            })),
          ),
          maybeNote: value.details.notes === null ? null : note(value.details.notes),
        },
});

const toWireDetails = (details: OperationDetails): Wire["OperationDetails"] =>
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

const requestFailure = (error: unknown): Error =>
  error instanceof Error ? error : new Error("journal request failed");
