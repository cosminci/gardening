import { describe, expect, it } from "vitest";
import { makeHttpJournalClient } from "../../../src/adapters/http/HttpJournalClient";
import {
  operationId,
  nomenclatureInfo,
  nomenclatureName,
  percentage,
  pesticideId,
  plantId,
  substrate,
  substrateComponentId,
} from "../../../src/domain/Journal";
import { jsonResponse, respondingWith } from "./HttpTestSupport";

const perliteId = substrateComponentId("00000000-0000-4000-8000-000000000003");
const pineBarkId = substrateComponentId("00000000-0000-4000-8000-000000000004");

describe("HttpJournalClient reads", () => {
  it("should translate plant and operation responses into domain values", async () => {
    const fetch = respondingWith([
      jsonResponse([
        {
          id: "p1",
          details: {
            species: "Ficus lyrata",
            nickname: "Fern",
            location: "Balcony",
            substrate: [
              {
                componentId: perliteId,
                share: 100,
              },
            ],
            status: "active",
          },
        },
        {
          id: "p2",
          details: {
            species: "Monstera deliciosa",
            nickname: null,
            location: "Kitchen",
            substrate: [
              {
                componentId: pineBarkId,
                share: 40,
              },
            ],
            status: "active",
          },
        },
      ]),
      jsonResponse([
        {
          id: "o1",
          plantId: "p1",
          date: "2026-01-01T00:00:00Z",
          details: {
            kind: "care",
            actions: ["watered", "pruned"],
            pesticides: ["00000000-0000-4000-8001-000000000003"],
            moisture: "wet",
            notes: "Recovered",
          },
        },
        {
          id: "o2",
          plantId: "p1",
          date: "2026-01-02T00:00:00Z",
          details: {
            kind: "care",
            actions: [],
            pesticides: [],
            moisture: "noReading",
            notes: null,
          },
        },
      ]),
    ]);
    const journal = makeHttpJournalClient(fetch);

    await expect(journal.getPlants()).resolves.toEqual({
      kind: "read",
      plants: [
        {
          id: plantId("p1"),
          details: {
            species: "Ficus lyrata",
            maybeNickname: "Fern",
            location: "Balcony",
            substrate: substrate([{ component: perliteId, share: percentage(100) }]),
            status: "active",
          },
        },
        {
          id: plantId("p2"),
          details: {
            species: "Monstera deliciosa",
            maybeNickname: null,
            location: "Kitchen",
            substrate: substrate([{ component: pineBarkId, share: percentage(40) }]),
            status: "active",
          },
        },
      ],
    });
    await expect(journal.getOperations(plantId("p1"))).resolves.toEqual({
      kind: "read",
      operations: [
        {
          id: operationId("o1"),
          plantId: plantId("p1"),
          date: "2026-01-01T00:00:00Z",
          details: {
            kind: "care",
            actions: new Set(["watered", "pruned"]),
            pesticides: new Set([pesticideId("00000000-0000-4000-8001-000000000003")]),
            moisture: "wet",
            maybeNote: "Recovered",
          },
        },
        {
          id: operationId("o2"),
          plantId: plantId("p1"),
          date: "2026-01-02T00:00:00Z",
          details: {
            kind: "care",
            actions: new Set(),
            pesticides: new Set(),
            moisture: "noReading",
            maybeNote: null,
          },
        },
      ],
    });
  });

  it("should translate substrate component and pesticide catalogs", async () => {
    const requests: Request[] = [];
    const fetch = respondingWith(
      [
        jsonResponse([
          {
            id: perliteId,
            data: { name: "Perlite", info: "Adds drainage" },
          },
        ]),
        jsonResponse([
          {
            id: "00000000-0000-4000-8001-000000000003",
            data: { name: "Neem oil", type: "insecticide", info: null },
          },
        ]),
      ],
      requests,
    );
    const journal = makeHttpJournalClient(fetch);

    await expect(journal.getSubstrateComponents()).resolves.toEqual({
      kind: "read",
      entries: [
        {
          id: perliteId,
          data: {
            name: nomenclatureName("Perlite"),
            maybeInfo: nomenclatureInfo("Adds drainage"),
          },
        },
      ],
    });
    await expect(journal.getPesticides()).resolves.toEqual({
      kind: "read",
      entries: [
        {
          id: pesticideId("00000000-0000-4000-8001-000000000003"),
          data: {
            name: nomenclatureName("Neem oil"),
            pesticideType: "insecticide",
            maybeInfo: null,
          },
        },
      ],
    });
    expect(requests.map((request) => `${request.method} ${new URL(request.url).pathname}`)).toEqual(
      ["GET /substrate-components", "GET /pesticides"],
    );
  });
});
