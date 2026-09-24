import type { components, paths } from "@contract";
import createClient from "openapi-fetch";
import * as Journal from "../../domain/Journal";

type Wire = components["schemas"];

export const makeHttpJournalClient = (
  fetch: (request: Request) => Promise<Response> = globalThis.fetch,
): Journal.JournalClient => {
  const client = createClient<paths>({ baseUrl: globalThis.location.origin, fetch });

  return {
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

    async getOperationDates(id): Promise<Journal.GetOperationDatesResult> {
      try {
        const { data, error } = await client.GET("/operations/date-range", {
          params: { query: { plantId: id } },
        });
        if (data === undefined) return { kind: "readFailed", reason: requestFailure(error) };
        if (data.kind === "empty") return { kind: "read", dates: { kind: "empty" } };
        if (
          typeof data.first === "string" &&
          typeof data.last === "string" &&
          Number.isFinite(Date.parse(data.first)) &&
          Date.parse(data.first) <= Date.parse(data.last)
        )
          return {
            kind: "read",
            dates: {
              kind: "recorded",
              first: Journal.instant(data.first),
              last: Journal.instant(data.last),
            },
          };
        return { kind: "readFailed", reason: new Error("invalid operation date range") };
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

    async getOperations(id, window): Promise<Journal.GetOperationsResult> {
      try {
        const { data, error } = await client.GET("/operations", {
          params: {
            query: { plantId: id, offset: window.offset, pageSize: window.size },
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

    async logOperation(id, date, details): Promise<Journal.LogOperationResult> {
      try {
        const { data, error, response } = await client.POST("/operations", {
          body: { plantId: id, date, details: toWireDetails(details) },
        });
        if (response.status === 409) return { kind: "plantArchived" };
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

const requestFailure = (error: unknown): Error =>
  error instanceof Error ? error : new Error("journal request failed");
