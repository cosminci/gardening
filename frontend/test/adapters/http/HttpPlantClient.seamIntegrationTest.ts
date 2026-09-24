import { describe, expect, it } from "vitest";
import { makeHttpPlantClient } from "../../../src/adapters/http/HttpPlantClient";
import * as Journal from "../../../src/domain/Journal";
import { jsonResponse, respondingWith } from "./HttpTestSupport";

const perliteId = Journal.substrateComponentId("00000000-0000-4000-8000-000000000003");
const plant = (id: string, nickname: string | null = null): Journal.Plant => ({
  id: Journal.plantId(id),
  details: {
    species: Journal.species("Ficus lyrata"),
    maybeNickname: nickname === null ? null : Journal.nickname(nickname),
    location: Journal.location("Balcony"),
    substrate: Journal.substrate([{ component: perliteId, share: Journal.percentage(100) }]),
    status: "active",
  },
});
const wirePlant = (value: Journal.Plant) => ({
  id: value.id,
  details: {
    species: value.details.species,
    nickname: value.details.maybeNickname,
    location: value.details.location,
    substrate: value.details.substrate.map((part) => ({
      componentId: part.component,
      share: part.share,
    })),
    status: value.details.status,
  },
});

describe("HttpPlantClient", () => {
  it("should read current plants with active default and archived status filtering", async () => {
    const activePlant = plant("p1", "Fern");
    const archivedPlant = {
      ...plant("p2"),
      details: { ...plant("p2").details, status: "archived" as const },
    };
    const requests: Request[] = [];
    const client = makeHttpPlantClient(
      respondingWith(
        [jsonResponse([wirePlant(activePlant)]), jsonResponse([wirePlant(archivedPlant)])],
        requests,
      ),
    );

    const activeResult = await client.getPlants();
    const archivedResult = await client.getPlants("archived");
    const requestedStatuses = requests.map((request) => new URL(request.url).search);
    const expectedArchivedResult = { kind: "read", plants: [archivedPlant] };

    expect(activeResult).toEqual({ kind: "read", plants: [activePlant] });
    expect(archivedResult).toEqual(expectedArchivedResult);
    expect(requestedStatuses).toEqual(["", "?status=archived"]);
  });

  it("should report failed plant reads instead of returning stale details", async () => {
    const reason = new Error("offline");
    const httpClient = makeHttpPlantClient(
      respondingWith([jsonResponse({ message: "plants unavailable" }, 503)]),
    );
    const networkClient = makeHttpPlantClient(respondingWith([reason]));

    await expect(httpClient.getPlants()).resolves.toMatchObject({ kind: "readFailed" });
    await expect(networkClient.getPlants()).resolves.toEqual({ kind: "readFailed", reason });
  });

  it("should read the archived count without fetching the archived plant list", async () => {
    const requests: Request[] = [];
    const client = makeHttpPlantClient(respondingWith([jsonResponse({ count: 7 })], requests));

    const result = await client.getArchivedCount();
    const paths = requests.map((request) => new URL(request.url).pathname);

    expect(result).toEqual({ kind: "read", count: 7 });
    expect(paths).toEqual(["/plants/archived/count"]);
  });

  it("should report invalid or unavailable archived counts", async () => {
    const client = makeHttpPlantClient(
      respondingWith([
        jsonResponse({ count: -1 }),
        jsonResponse({ message: "count unavailable" }, 503),
        new Error("offline"),
      ]),
    );

    const malformedCount = await client.getArchivedCount();
    const failedCount = await client.getArchivedCount();
    const offlineCount = await client.getArchivedCount();

    expect(malformedCount).toMatchObject({ kind: "readFailed" });
    expect(failedCount).toMatchObject({ kind: "readFailed" });
    expect(offlineCount).toMatchObject({ kind: "readFailed" });
  });

  it("should translate irreversible archive outcomes", async () => {
    const requests: Request[] = [];
    const client = makeHttpPlantClient(
      respondingWith(
        [
          new Response(null, { status: 204 }),
          jsonResponse({ message: "missing" }, 404),
          jsonResponse({ message: "already archived" }, 409),
          jsonResponse({ message: "archive unavailable" }, 500),
        ],
        requests,
      ),
    );
    const plantId = Journal.plantId("p1");
    const offline = makeHttpPlantClient(respondingWith([new Error("offline")]));

    const archived = await client.archivePlant(plantId);
    const missing = await client.archivePlant(plantId);
    const alreadyArchived = await client.archivePlant(plantId);
    const failed = await client.archivePlant(plantId);
    const offlineResult = await offline.archivePlant(plantId);

    expect(archived).toEqual({ kind: "archived" });
    expect(missing).toEqual({ kind: "plantMissing" });
    expect(alreadyArchived).toEqual({ kind: "alreadyArchived" });
    expect(failed).toMatchObject({ kind: "archiveFailed" });
    const requestsMade = await Promise.all(
      requests.map(async (request) => {
        const body: unknown = await request.json();
        return {
          method: request.method,
          path: new URL(request.url).pathname,
          contentType: request.headers.get("content-type"),
          body,
        };
      }),
    );
    expect(requestsMade).toEqual(
      Array(4).fill({
        method: "PATCH",
        path: "/plants/p1",
        contentType: "application/json-patch+json",
        body: [{ op: "replace", path: "/details/status", value: "archived" }],
      }),
    );
    expect(offlineResult).toMatchObject({ kind: "archiveFailed" });
  });
});
