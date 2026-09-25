import type { components, paths } from "@contract";
import createClient from "openapi-fetch";
import * as Journal from "../../domain/Journal";
import type { PesticideClient } from "../../domain/PesticideCatalog";

type Wire = components["schemas"];

export const makeHttpPesticideClient = (
  fetch: (request: Request) => Promise<Response> = globalThis.fetch,
): PesticideClient => {
  const client = createClient<paths>({ baseUrl: globalThis.location.origin, fetch });

  return {
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

    async editPesticide(id, value): Promise<Journal.PesticideEditResult> {
      try {
        const { data, error, response } = await client.PUT("/pesticides/{pesticideId}", {
          params: { path: { pesticideId: id } },
          body: toWirePesticideData(value),
        });
        if (data !== undefined) return { kind: "edited", entry: toPesticide(data) };
        if (response.status === 404) return { kind: "pesticideMissing" };
        if (response.status === 409) return { kind: "pesticideArchived" };
        return { kind: "editFailed", reason: requestFailure(error) };
      } catch (error) {
        return { kind: "editFailed", reason: requestFailure(error) };
      }
    },

    async archivePesticide(id): Promise<Journal.PesticideArchiveResult> {
      try {
        const { data, error, response } = await client.POST("/pesticides/{pesticideId}/archive", {
          params: { path: { pesticideId: id } },
        });
        if (data !== undefined) return { kind: "archived", entry: toPesticide(data) };
        if (response.status === 404) return { kind: "pesticideMissing" };
        if (response.status === 409) return { kind: "alreadyArchived" };
        return { kind: "archiveFailed", reason: requestFailure(error) };
      } catch (error) {
        return { kind: "archiveFailed", reason: requestFailure(error) };
      }
    },
  };
};

const toPesticide = (value: Wire["Pesticide"]): Journal.Pesticide => ({
  id: Journal.pesticideId(value.id),
  data: {
    name: Journal.pesticideName(value.data.name),
    pesticideType: value.data.type,
    maybeInfo: value.data.info === null ? null : Journal.pesticideInfo(value.data.info),
  },
  status: value.status,
});

const toWirePesticideData = (value: Journal.PesticideData): Wire["PesticideData"] => ({
  name: value.name,
  type: value.pesticideType,
  info: value.maybeInfo,
});

const requestFailure = (error: unknown): Error =>
  error instanceof Error ? error : new Error("journal request failed");
