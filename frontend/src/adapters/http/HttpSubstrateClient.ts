import type { components, paths } from "@contract";
import createClient from "openapi-fetch";
import * as Journal from "../../domain/Journal";
import type { SubstrateClient } from "../../domain/SubstrateCatalog";
import { fromWireSubstrate, toWireSubstrate } from "./Codecs";

type Wire = components["schemas"];

export const makeHttpSubstrateClient = (
  fetch: (request: Request) => Promise<Response> = globalThis.fetch,
): SubstrateClient => {
  const client = createClient<paths>({ baseUrl: globalThis.location.origin, fetch });

  return {
    async getSubstrateComponents(): Promise<Journal.CatalogReadResult<Journal.SubstrateComponent>> {
      try {
        const { data, error } = await client.GET("/substrates/components");
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
        const { data, error } = await client.POST("/substrates/components", {
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
        const { data, error, response } = await client.PUT("/substrates/components/{componentId}", {
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

    async getSubstrateMixes(): Promise<Journal.CatalogReadResult<Journal.SubstrateMix>> {
      try {
        const { data, error } = await client.GET("/substrates/mixes");
        return data === undefined
          ? { kind: "readFailed", reason: requestFailure(error) }
          : { kind: "read", entries: data.map(toSubstrateMix) };
      } catch (error) {
        return { kind: "readFailed", reason: requestFailure(error) };
      }
    },

    async addSubstrateMix(name, maybeNotes, substrate): Promise<Journal.AddSubstrateMixResult> {
      try {
        const { data, error, response } = await client.POST("/substrates/mixes", {
          body: { name, notes: maybeNotes, substrate: toWireSubstrate(substrate) },
        });
        if (data !== undefined) return { kind: "added", entry: toSubstrateMix(data) };
        if (response.status === 409) return { kind: "duplicateSubstrate" };
        return { kind: "addFailed", reason: requestFailure(error) };
      } catch (error) {
        return { kind: "addFailed", reason: requestFailure(error) };
      }
    },

    async deleteSubstrateMix(id): Promise<Journal.CatalogDeleteResult> {
      try {
        const { error } = await client.DELETE("/substrates/mixes/{mixId}", {
          params: { path: { mixId: id } },
        });
        return error === undefined
          ? { kind: "deleted" }
          : { kind: "deleteFailed", reason: requestFailure(error) };
      } catch (error) {
        return { kind: "deleteFailed", reason: requestFailure(error) };
      }
    },
  };
};

const toSubstrateComponent = (value: Wire["SubstrateComponent"]): Journal.SubstrateComponent => ({
  id: Journal.substrateComponentId(value.id),
  data: {
    name: Journal.substrateComponentName(value.data.name),
    maybeInfo: value.data.info === null ? null : Journal.substrateComponentInfo(value.data.info),
  },
});

const toWireSubstrateComponentData = (
  value: Journal.SubstrateComponentData,
): Wire["SubstrateComponentData"] => ({
  name: value.name,
  info: value.maybeInfo,
});

const toSubstrateMix = (value: Wire["SubstrateMix"]): Journal.SubstrateMix => ({
  id: Journal.substrateMixId(value.id),
  name: Journal.substrateMixName(value.name),
  maybeNotes: value.notes === null ? null : Journal.substrateMixNotes(value.notes),
  substrate: fromWireSubstrate(value.substrate),
});

const requestFailure = (error: unknown): Error =>
  error instanceof Error ? error : new Error("journal request failed");
