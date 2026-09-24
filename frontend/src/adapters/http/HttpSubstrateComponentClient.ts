import type { components, paths } from "@contract";
import createClient from "openapi-fetch";
import * as Journal from "../../domain/Journal";
import type { SubstrateComponentClient } from "../../domain/SubstrateComponentCatalog";

type Wire = components["schemas"];

export const makeHttpSubstrateComponentClient = (
  fetch: (request: Request) => Promise<Response> = globalThis.fetch,
): SubstrateComponentClient => {
  const client = createClient<paths>({ baseUrl: globalThis.location.origin, fetch });

  return {
    async getSubstrateComponents(): Promise<Journal.CatalogReadResult<Journal.SubstrateComponent>> {
      try {
        const { data, error } = await client.GET("/substrate/components");
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
        const { data, error } = await client.POST("/substrate/components", {
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
        const { data, error, response } = await client.PUT("/substrate/components/{componentId}", {
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
  };
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

const requestFailure = (error: unknown): Error =>
  error instanceof Error ? error : new Error("journal request failed");
