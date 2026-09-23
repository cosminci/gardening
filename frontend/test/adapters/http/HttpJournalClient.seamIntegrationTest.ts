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

    await expect(journal.getPlants()).resolves.toEqual({ kind: "read", plants: [activePlant] });
    await expect(journal.getPlants("archived")).resolves.toEqual({
      kind: "read",
      plants: [archivedPlant],
    });

    expect(requests.map((request) => new URL(request.url).search)).toEqual([
      "",
      "?status=archived",
    ]);
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

    await expect(
      journal.getOperations(Journal.plantId("p1"), { offset: 0, size: 3 }),
    ).resolves.toEqual({
      kind: "read",
      page: { operations: expectedOperations, hasNextPage: true },
    });
    expect(requests[0]?.url).toContain("/plants/p1/operations?offset=0&pageSize=3");
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
    const expectedComponents = [
      {
        id: perliteId,
        data: {
          name: Journal.nomenclatureName("Perlite"),
          maybeInfo: Journal.nomenclatureInfo("Adds drainage"),
        },
      },
    ];
    const expectedPesticides = [
      {
        id: Journal.pesticideId("00000000-0000-4000-8001-000000000003"),
        data: {
          name: Journal.nomenclatureName("Neem oil"),
          pesticideType: "insecticide",
          maybeInfo: null,
        },
      },
    ];

    await expect(journal.getSubstrateComponents()).resolves.toEqual({
      kind: "read",
      entries: expectedComponents,
    });
    await expect(journal.getPesticides()).resolves.toEqual({
      kind: "read",
      entries: expectedPesticides,
    });
    expect(requests.map((request) => `${request.method} ${new URL(request.url).pathname}`)).toEqual(
      ["GET /substrate-components", "GET /pesticides"],
    );
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

    await expect(
      journal.logOperation(Journal.plantId("p1"), Journal.instant("2026-01-01T00:00:00Z"), care),
    ).resolves.toEqual({
      kind: "logged",
      id: Journal.operationId("logged"),
    });
    await expect(journal.editOperation(Journal.operationId("o1"), repot)).resolves.toEqual({
      kind: "operationTypeMismatch",
    });
    await expect(journal.editOperation(Journal.operationId("o1"), repot)).resolves.toMatchObject({
      kind: "edited",
      operation: expectedOperation,
    });
    await expect(journal.editOperation(Journal.operationId("o1"), repot)).resolves.toMatchObject({
      operation: { details: { maybeNote: null } },
    });
    expect(requests.map((request) => `${request.method} ${new URL(request.url).pathname}`)).toEqual(
      [
        "POST /plants/p1/operations",
        "PUT /operations/o1",
        "PUT /operations/o1",
        "PUT /operations/o1",
      ],
    );
    await expect(Promise.all(requests.map((request) => request.json()))).resolves.toEqual([
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
    ]);
  });

  it("should send substrate component and pesticide catalog changes", async () => {
    const requests: Request[] = [];
    const pesticide = Journal.pesticideId("00000000-0000-4000-8001-000000000003");
    const fetch = respondingWith(
      [
        jsonResponse({ id: perliteId, data: { name: "Perlite", info: "Adds drainage" } }, 201),
        jsonResponse({ id: perliteId, data: { name: "Perlite fine", info: null } }),
        jsonResponse(
          {
            id: pesticide,
            data: { name: "Neem oil", type: "insecticide", info: "Dilute first" },
          },
          201,
        ),
        jsonResponse({
          id: pesticide,
          data: { name: "Neem", type: "insecticide", info: null },
        }),
      ],
      requests,
    );
    const journal = makeHttpJournalClient(fetch);

    await expect(
      journal.addSubstrateComponent({
        name: Journal.nomenclatureName("Perlite"),
        maybeInfo: Journal.nomenclatureInfo("Adds drainage"),
      }),
    ).resolves.toMatchObject({ kind: "added", entry: { id: perliteId } });
    await expect(
      journal.editSubstrateComponent(perliteId, {
        name: Journal.nomenclatureName("Perlite fine"),
        maybeInfo: null,
      }),
    ).resolves.toMatchObject({
      kind: "edited",
      entry: { data: { name: "Perlite fine", maybeInfo: null } },
    });
    await expect(
      journal.addPesticide({
        name: Journal.nomenclatureName("Neem oil"),
        pesticideType: "insecticide",
        maybeInfo: Journal.nomenclatureInfo("Dilute first"),
      }),
    ).resolves.toMatchObject({ kind: "added", entry: { id: pesticide } });
    await expect(
      journal.editPesticide(pesticide, {
        name: Journal.nomenclatureName("Neem"),
        pesticideType: "insecticide",
        maybeInfo: null,
      }),
    ).resolves.toMatchObject({
      kind: "edited",
      entry: { data: { name: "Neem", pesticideType: "insecticide", maybeInfo: null } },
    });

    expect(requests.map((request) => `${request.method} ${new URL(request.url).pathname}`)).toEqual(
      [
        "POST /substrate-components",
        `PUT /substrate-components/${perliteId}`,
        "POST /pesticides",
        `PUT /pesticides/${pesticide}`,
      ],
    );
    await expect(Promise.all(requests.map((request) => request.json()))).resolves.toEqual([
      { name: "Perlite", info: "Adds drainage" },
      { name: "Perlite fine", info: null },
      {
        name: "Neem oil",
        type: "insecticide",
        info: "Dilute first",
      },
      {
        name: "Neem",
        type: "insecticide",
        info: null,
      },
    ]);
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
        jsonResponse({ message: "catalog could not be read" }, 500),
        jsonResponse({ message: "catalog could not be changed" }, 500),
        jsonResponse({ message: "component not found" }, 404),
        jsonResponse({ message: "catalog could not be changed" }, 500),
        jsonResponse({ message: "catalog could not be read" }, 500),
        jsonResponse({ message: "catalog could not be changed" }, 500),
        jsonResponse({ message: "pesticide not found" }, 404),
        jsonResponse({ message: "catalog could not be changed" }, 500),
      ]),
    );
    const component = Journal.substrateComponentId("00000000-0000-4000-8000-000000000003");
    const pesticide = Journal.pesticideId("00000000-0000-4000-8001-000000000003");
    const componentData = { name: Journal.nomenclatureName("Perlite"), maybeInfo: null };
    const pesticideData = {
      name: Journal.nomenclatureName("Neem"),
      pesticideType: "insecticide" as const,
      maybeInfo: null,
    };
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
    await expectKind(httpFailures.getSubstrateComponents(), "readFailed");
    await expectKind(httpFailures.addSubstrateComponent(componentData), "addFailed");
    await expectResult(httpFailures.editSubstrateComponent(component, componentData), {
      kind: "recordMissing",
    });
    await expectKind(httpFailures.editSubstrateComponent(component, componentData), "editFailed");
    await expectKind(httpFailures.getPesticides(), "readFailed");
    await expectKind(httpFailures.addPesticide(pesticideData), "addFailed");
    await expectResult(httpFailures.editPesticide(pesticide, pesticideData), {
      kind: "recordMissing",
    });
    await expectKind(httpFailures.editPesticide(pesticide, pesticideData), "editFailed");

    const reason = new Error("offline");
    const networkFailures = makeHttpJournalClient(
      respondingWith(new Array<Error>(10).fill(reason)),
    );
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
    await expectNetworkFailure(networkFailures.getSubstrateComponents(), "readFailed");
    await expectNetworkFailure(networkFailures.addSubstrateComponent(componentData), "addFailed");
    await expectNetworkFailure(
      networkFailures.editSubstrateComponent(component, componentData),
      "editFailed",
    );
    await expectNetworkFailure(networkFailures.getPesticides(), "readFailed");
    await expectNetworkFailure(networkFailures.addPesticide(pesticideData), "addFailed");
    await expectNetworkFailure(
      networkFailures.editPesticide(pesticide, pesticideData),
      "editFailed",
    );
  });
});
