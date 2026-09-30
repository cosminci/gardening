import type { components, paths } from "@contract";
import createClient from "openapi-fetch";
import * as Journal from "../../domain/Journal";
import type { OperationClient } from "../../domain/Operation";
import { fromWireSubstrate, toWireSubstrate } from "./Codecs";
import { coerceToError } from "./HttpError";

type Wire = components["schemas"];

export const makeHttpOperationClient = (
  fetch: (request: Request) => Promise<Response> = globalThis.fetch,
): OperationClient => {
  const client = createClient<paths>({ baseUrl: globalThis.location.origin, fetch });

  return {
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

    async deleteOperation(id): Promise<Journal.DeleteOperationResult> {
      try {
        const { error, response } = await client.DELETE("/operations/{operationId}", {
          params: { path: { operationId: id } },
        });
        if (response.status === 204) return { kind: "deleted" };
        if (response.status === 404) return { kind: "operationMissing" };
        if (response.status === 409) return { kind: "cannotDeleteLatestRepot" };
        return { kind: "deleteFailed", reason: requestFailure(error) };
      } catch (error) {
        return { kind: "deleteFailed", reason: requestFailure(error) };
      }
    },
  };
};

const toOperation = (value: Wire["Operation"]): Journal.Operation => ({
  id: Journal.operationId(value.id),
  plant: Journal.plantId(value.plantId),
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
          substrate: fromWireSubstrate(value.details.substrate),
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
        substrate: toWireSubstrate(details.substrate),
        notes: details.maybeNote,
      };

const requestFailure = (error: unknown): Error => coerceToError(error, "operation request failed");
