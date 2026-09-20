import { describe, expect, it } from "vitest";
import { makeHttpJournalClient } from "../../../src/adapters/http/HttpJournalClient";
import { operationId, percentage, plantId, substrate } from "../../../src/domain/Journal";
import { jsonResponse, respondingWith } from "./HttpTestSupport";

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
            substrate: [{ component: "perlite", share: 100 }],
            status: "active",
          },
        },
        {
          id: "p2",
          details: {
            species: "Monstera deliciosa",
            nickname: null,
            location: "Kitchen",
            substrate: [{ component: "pineBark", share: 40 }],
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
            substrate: substrate([{ component: "perlite", share: percentage(100) }]),
            status: "active",
          },
        },
        {
          id: plantId("p2"),
          details: {
            species: "Monstera deliciosa",
            maybeNickname: null,
            location: "Kitchen",
            substrate: substrate([{ component: "pineBark", share: percentage(40) }]),
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
            moisture: "noReading",
            maybeNote: null,
          },
        },
      ],
    });
  });
});
