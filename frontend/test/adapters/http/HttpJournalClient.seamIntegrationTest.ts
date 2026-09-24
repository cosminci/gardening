import { describe, expect, it } from "vitest";
import { makeHttpJournalClient } from "../../../src/adapters/http/HttpJournalClient";
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
const expectKind = (result: Promise<unknown>, kind: string) =>
  expect(result).resolves.toMatchObject({ kind });
const expectResult = (result: Promise<unknown>, expected: object) =>
  expect(result).resolves.toEqual(expected);

describe("HttpJournalClient", () => {
  it("should read current plants with active default and archived status filtering", async () => {
    const activePlant = plant("p1", "Fern");
    const archivedPlant = {
      ...plant("p2"),
      details: { ...plant("p2").details, status: "archived" as const },
    };
    const requests: Request[] = [];
    const journal = makeHttpJournalClient(
      respondingWith(
        [jsonResponse([wirePlant(activePlant)]), jsonResponse([wirePlant(archivedPlant)])],
        requests,
      ),
    );

    const activeResult = await journal.getPlants();
    const archivedResult = await journal.getPlants("archived");
    const requestedStatuses = requests.map((request) => new URL(request.url).search);
    const expectedArchivedResult = { kind: "read", plants: [archivedPlant] };

    expect(activeResult).toEqual({ kind: "read", plants: [activePlant] });
    expect(archivedResult).toEqual(expectedArchivedResult);
    expect(requestedStatuses).toEqual(["", "?status=archived"]);
  });

  it("should report failed plant reads instead of returning stale details", async () => {
    const reason = new Error("offline");
    const httpJournal = makeHttpJournalClient(
      respondingWith([jsonResponse({ message: "plants unavailable" }, 503)]),
    );
    const networkJournal = makeHttpJournalClient(respondingWith([reason]));

    await expect(httpJournal.getPlants()).resolves.toMatchObject({ kind: "readFailed" });
    await expect(networkJournal.getPlants()).resolves.toEqual({ kind: "readFailed", reason });
  });

  it("should read the archived count without fetching the archived plant list", async () => {
    const requests: Request[] = [];
    const journal = makeHttpJournalClient(respondingWith([jsonResponse({ count: 7 })], requests));

    const result = await journal.getArchivedCount();
    const paths = requests.map((request) => new URL(request.url).pathname);

    expect(result).toEqual({ kind: "read", count: 7 });
    expect(paths).toEqual(["/plants/archived/count"]);
  });

  it("should read the first and last operation dates without paging through history", async () => {
    const requests: Request[] = [];
    const journal = makeHttpJournalClient(
      respondingWith(
        [
          jsonResponse({
            kind: "recorded",
            first: "2026-02-01T10:00:00Z",
            last: "2026-04-03T18:00:00Z",
          }),
          jsonResponse({ kind: "empty" }),
        ],
        requests,
      ),
    );
    const plantId = Journal.plantId("p1");

    const recordedResult = await journal.getOperationDates(plantId);
    const emptyResult = await journal.getOperationDates(plantId);
    const expectedDates = {
      kind: "recorded",
      first: Journal.instant("2026-02-01T10:00:00Z"),
      last: Journal.instant("2026-04-03T18:00:00Z"),
    };

    expect(recordedResult).toEqual({ kind: "read", dates: expectedDates });
    expect(emptyResult).toEqual({ kind: "read", dates: { kind: "empty" } });
    expect(requests).toHaveLength(2);
  });

  it("should report invalid or unavailable archived counts", async () => {
    const journal = makeHttpJournalClient(
      respondingWith([
        jsonResponse({ count: -1 }),
        jsonResponse({ message: "count unavailable" }, 503),
        new Error("offline"),
      ]),
    );

    const malformedCount = await journal.getArchivedCount();
    const failedCount = await journal.getArchivedCount();
    const offlineCount = await journal.getArchivedCount();

    expect(malformedCount).toMatchObject({ kind: "readFailed" });
    expect(failedCount).toMatchObject({ kind: "readFailed" });
    expect(offlineCount).toMatchObject({ kind: "readFailed" });
  });

  it("should reject incomplete or unavailable recorded operation dates", async () => {
    const journal = makeHttpJournalClient(
      respondingWith([
        jsonResponse({ kind: "recorded", first: "2026-02-01T10:00:00Z", last: null }),
        jsonResponse({ message: "missing plant" }, 404),
        new Error("offline"),
      ]),
    );
    const plantId = Journal.plantId("p1");

    const incompleteDates = await journal.getOperationDates(plantId);
    const missingDates = await journal.getOperationDates(plantId);
    const offlineDates = await journal.getOperationDates(plantId);

    expect(incompleteDates).toMatchObject({ kind: "readFailed" });
    expect(missingDates).toMatchObject({ kind: "readFailed" });
    expect(offlineDates).toMatchObject({ kind: "readFailed" });
  });

  it("should translate irreversible archive outcomes", async () => {
    const requests: Request[] = [];
    const journal = makeHttpJournalClient(
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
    const offline = makeHttpJournalClient(respondingWith([new Error("offline")]));

    const archived = await journal.archivePlant(plantId);
    const missing = await journal.archivePlant(plantId);
    const alreadyArchived = await journal.archivePlant(plantId);
    const failed = await journal.archivePlant(plantId);
    const offlineResult = await offline.archivePlant(plantId);

    expect(archived).toEqual({ kind: "archived" });
    expect(missing).toEqual({ kind: "plantMissing" });
    expect(alreadyArchived).toEqual({ kind: "alreadyArchived" });
    expect(failed).toMatchObject({ kind: "archiveFailed" });
    expect(requests).toHaveLength(4);
    expect(offlineResult).toMatchObject({ kind: "archiveFailed" });
  });

  it("should reject logging an operation after its plant has been archived", async () => {
    const journal = makeHttpJournalClient(
      respondingWith([jsonResponse({ message: "archived plant" }, 409)]),
    );
    const plantId = Journal.plantId("p1");
    const date = Journal.instant("2026-04-03T18:00:00Z");
    const care: Journal.OperationDetails = {
      kind: "care",
      actions: new Set(["watered"]),
      pesticides: new Set(),
      moisture: "wet",
      maybeNote: null,
    };

    const result = await journal.logOperation(plantId, date, care);

    expect(result).toEqual({ kind: "plantArchived" });
  });

  it("should translate the attention projection into domain values", async () => {
    const noSamples = plant("no-samples");
    const unknown = plant("unknown");
    const finite = plant("finite", "Fern");
    const unbounded = plant("unbounded");
    const current = plant("current");
    const redAlert = plant("red-alert");
    const requests: Request[] = [];
    const journal = makeHttpJournalClient(
      respondingWith(
        [
          jsonResponse({
            measuredAt: "2026-01-10T00:00:00Z",
            plants: [
              {
                plantId: noSamples.id,
                watering: { kind: "unavailable", sampleCount: 0, elapsedMillis: null },
              },
              {
                plantId: unknown.id,
                watering: { kind: "unavailable", sampleCount: 4, elapsedMillis: "86400000" },
              },
              {
                plantId: finite.id,
                watering: {
                  kind: "overdue",
                  sampleCount: 5,
                  averageIntervalMillis: "86400000",
                  elapsedMillis: "90000000",
                },
              },
              {
                plantId: unbounded.id,
                watering: {
                  kind: "overdue",
                  sampleCount: 5,
                  averageIntervalMillis: "0",
                  elapsedMillis: "1000",
                },
              },
              {
                plantId: current.id,
                watering: {
                  kind: "current",
                  sampleCount: 5,
                  averageIntervalMillis: "86400000",
                  elapsedMillis: "43200000",
                },
              },
              {
                plantId: redAlert.id,
                watering: {
                  kind: "redAlert",
                  sampleCount: 5,
                  averageIntervalMillis: "86400000",
                  elapsedMillis: "176400000",
                },
              },
            ],
          }),
        ],
        requests,
      ),
    );

    await expect(journal.getAttention()).resolves.toEqual({
      kind: "read",
      projection: {
        measuredAt: Journal.instant("2026-01-10T00:00:00Z"),
        plants: [
          {
            plantId: noSamples.id,
            watering: {
              kind: "unavailable",
              sampleCount: 0,
              maybeElapsed: null,
            },
          },
          {
            plantId: unknown.id,
            watering: {
              kind: "unavailable",
              sampleCount: 4,
              maybeElapsed: Journal.milliseconds("86400000"),
            },
          },
          {
            plantId: finite.id,
            watering: {
              kind: "overdue",
              sampleCount: 5,
              averageInterval: Journal.milliseconds("86400000"),
              elapsed: Journal.milliseconds("90000000"),
            },
          },
          {
            plantId: unbounded.id,
            watering: {
              kind: "overdue",
              sampleCount: 5,
              averageInterval: Journal.milliseconds("0"),
              elapsed: Journal.milliseconds("1000"),
            },
          },
          {
            plantId: current.id,
            watering: {
              kind: "current",
              sampleCount: 5,
              averageInterval: Journal.milliseconds("86400000"),
              elapsed: Journal.milliseconds("43200000"),
            },
          },
          {
            plantId: redAlert.id,
            watering: {
              kind: "redAlert",
              sampleCount: 5,
              averageInterval: Journal.milliseconds("86400000"),
              elapsed: Journal.milliseconds("176400000"),
            },
          },
        ],
      },
    });
    expect(new URL(requests.at(0)?.url ?? "").pathname).toBe("/attention");
  });

  it("should reject malformed watering-attention measurements", async () => {
    const journal = makeHttpJournalClient(
      respondingWith([
        jsonResponse({
          measuredAt: "2026-01-10T00:00:00Z",
          plants: [
            {
              plantId: plant("p1").id,
              watering: {
                kind: "overdue",
                sampleCount: 5,
                averageIntervalMillis: "86400000",
                elapsedMillis: "soon",
              },
            },
          ],
        }),
      ]),
    );

    await expect(journal.getAttention()).resolves.toEqual({
      kind: "readFailed",
      reason: new RangeError("invalid milliseconds: soon"),
    });
  });

  it("should reject an unknown watering-attention classification", async () => {
    const journal = makeHttpJournalClient(
      respondingWith([
        jsonResponse({
          measuredAt: "2026-01-10T00:00:00Z",
          plants: [
            {
              plantId: plant("p1").id,
              watering: {
                kind: "futureState",
                sampleCount: 5,
                averageIntervalMillis: "86400000",
                elapsedMillis: "90000000",
              },
            },
          ],
        }),
      ]),
    );

    await expect(journal.getAttention()).resolves.toEqual({
      kind: "readFailed",
      reason: new Error(
        'invalid watering attention: {"kind":"futureState","sampleCount":5,"averageIntervalMillis":"86400000","elapsedMillis":"90000000"}',
      ),
    });
  });

  it("should translate plant and operation responses into domain values", async () => {
    const pesticide = Journal.pesticideId("00000000-0000-4000-8001-000000000003");
    const requests: Request[] = [];
    const fetch = respondingWith(
      [
        jsonResponse({
          operations: [
            {
              id: "o1",
              plantId: "p1",
              date: "2026-01-01T00:00:00Z",
              details: {
                kind: "care",
                actions: ["watered", "pruned"],
                pesticides: [pesticide],
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
          ],
          hasNextPage: true,
        }),
      ],
      requests,
    );
    const journal = makeHttpJournalClient(fetch);
    const expectedPesticides = new Set([pesticide]);
    const expectedOperations = [
      {
        id: Journal.operationId("o1"),
        plantId: Journal.plantId("p1"),
        date: "2026-01-01T00:00:00Z",
        details: {
          kind: "care",
          actions: new Set(["watered", "pruned"]),
          pesticides: expectedPesticides,
          moisture: "wet",
          maybeNote: "Recovered",
        },
      },
      {
        id: Journal.operationId("o2"),
        plantId: Journal.plantId("p1"),
        date: "2026-01-02T00:00:00Z",
        details: {
          kind: "care",
          actions: new Set(),
          pesticides: new Set(),
          moisture: "noReading",
          maybeNote: null,
        },
      },
    ];

    const operationsResult = await journal.getOperations(Journal.plantId("p1"), {
      offset: 0,
      size: 3,
    });
    const expectedResult = {
      kind: "read",
      page: { operations: expectedOperations, hasNextPage: true },
    };
    expect(operationsResult).toEqual(expectedResult);
    expect(requests[0]?.url).toContain("/plants/p1/operations?offset=0&pageSize=3");
  });

  it("should send operation details and preserve write outcomes", async () => {
    const requests: Request[] = [];
    const repotResponse = (notes: string | null) =>
      jsonResponse({
        id: "o1",
        plantId: "p1",
        date: "2026-01-01T00:00:00Z",
        details: {
          kind: "repot",
          substrate: [{ componentId: perliteId, share: 80 }],
          notes,
        },
      });
    const fetch = respondingWith(
      [
        jsonResponse({ id: "logged" }, 201),
        jsonResponse({ message: "operation type cannot be changed" }, 409),
        repotResponse("Fresh"),
        repotResponse(null),
      ],
      requests,
    );
    const journal = makeHttpJournalClient(fetch);
    const care: Journal.OperationDetails = {
      kind: "care",
      actions: new Set(["watered"]),
      pesticides: new Set([Journal.pesticideId("00000000-0000-4000-8001-000000000003")]),
      moisture: "wet",
      maybeNote: null,
    };
    const repot: Journal.OperationDetails = {
      kind: "repot",
      substrate: Journal.substrate([{ component: perliteId, share: Journal.percentage(80) }]),
      maybeNote: Journal.note("Fresh"),
    };
    const expectedOperation = {
      details: {
        kind: "repot",
        substrate: Journal.substrate([{ component: perliteId, share: Journal.percentage(80) }]),
        maybeNote: Journal.note("Fresh"),
      },
    };

    const loggedResult = await journal.logOperation(
      Journal.plantId("p1"),
      Journal.instant("2026-01-01T00:00:00Z"),
      care,
    );
    const mismatchResult = await journal.editOperation(Journal.operationId("o1"), repot);
    const editedResult = await journal.editOperation(Journal.operationId("o1"), repot);
    const clearedResult = await journal.editOperation(Journal.operationId("o1"), repot);
    const requestedPaths = requests.map(
      (request) => `${request.method} ${new URL(request.url).pathname}`,
    );
    const actualRequests = await Promise.all(requests.map((request) => request.json()));

    const expectedLogged = { kind: "logged", id: Journal.operationId("logged") };
    const expectedPaths = [
      "POST /plants/p1/operations",
      "PUT /operations/o1",
      "PUT /operations/o1",
      "PUT /operations/o1",
    ];
    const expectedRequests = [
      {
        date: "2026-01-01T00:00:00Z",
        details: {
          kind: "care",
          actions: ["watered"],
          pesticides: ["00000000-0000-4000-8001-000000000003"],
          moisture: "wet",
          notes: null,
        },
      },
      {
        kind: "repot",
        substrate: [{ componentId: perliteId, share: 80 }],
        notes: "Fresh",
      },
      {
        kind: "repot",
        substrate: [{ componentId: perliteId, share: 80 }],
        notes: "Fresh",
      },
      {
        kind: "repot",
        substrate: [{ componentId: perliteId, share: 80 }],
        notes: "Fresh",
      },
    ];
    expect(loggedResult).toEqual(expectedLogged);
    expect(mismatchResult).toEqual({ kind: "operationTypeMismatch" });
    expect(editedResult).toMatchObject({ kind: "edited", operation: expectedOperation });
    expect(clearedResult).toMatchObject({ operation: { details: { maybeNote: null } } });
    expect(requestedPaths).toEqual(expectedPaths);
    expect(actualRequests).toEqual(expectedRequests);
  });

  it("should translate HTTP and network failures into explicit domain failures", async () => {
    const details = {
      kind: "care" as const,
      actions: new Set<never>(),
      pesticides: new Set<never>(),
      moisture: "noReading" as const,
      maybeNote: null,
    };
    const httpFailures = makeHttpJournalClient(
      respondingWith([
        jsonResponse({ message: "attention unavailable" }, 503),
        jsonResponse({ message: "journal could not be read" }, 500),
        jsonResponse("invalid body", 400),
        jsonResponse({ message: "operation not found" }, 404),
        jsonResponse({ message: "journal could not be changed" }, 500),
      ]),
    );
    await expectKind(httpFailures.getAttention(), "readFailed");
    await expectKind(
      httpFailures.getOperations(Journal.plantId("p1"), { offset: 0, size: 3 }),
      "readFailed",
    );
    await expectKind(
      httpFailures.logOperation(
        Journal.plantId("p1"),
        Journal.instant("2026-01-01T00:00:00Z"),
        details,
      ),
      "loggingFailed",
    );
    await expectResult(httpFailures.editOperation(Journal.operationId("missing"), details), {
      kind: "operationMissing",
    });
    await expectKind(httpFailures.editOperation(Journal.operationId("o1"), details), "editFailed");
    const reason = new Error("offline");
    const networkFailures = makeHttpJournalClient(respondingWith(new Array<Error>(4).fill(reason)));
    const expectNetworkFailure = (result: Promise<unknown>, kind: string) =>
      expect(result).resolves.toEqual({ kind, reason });
    await expectNetworkFailure(networkFailures.getAttention(), "readFailed");
    await expectNetworkFailure(
      networkFailures.getOperations(Journal.plantId("p1"), { offset: 0, size: 3 }),
      "readFailed",
    );
    await expectNetworkFailure(
      networkFailures.logOperation(
        Journal.plantId("p1"),
        Journal.instant("2026-01-01T00:00:00Z"),
        details,
      ),
      "loggingFailed",
    );
    await expectNetworkFailure(
      networkFailures.editOperation(Journal.operationId("o1"), details),
      "editFailed",
    );
  });
});
