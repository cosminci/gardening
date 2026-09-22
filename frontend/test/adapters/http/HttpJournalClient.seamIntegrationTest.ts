import { describe, expect, it } from "vitest";
import { makeHttpJournalClient } from "../../../src/adapters/http/HttpJournalClient";
import type { OperationDetails } from "../../../src/domain/Journal";
import {
  nomenclatureInfo,
  nomenclatureName,
  note,
  operationId,
  percentage,
  pesticideId,
  plantId,
  substrate,
  substrateComponentId,
} from "../../../src/domain/Journal";
import { jsonResponse, respondingWith } from "./HttpTestSupport";

const perliteId = substrateComponentId("00000000-0000-4000-8000-000000000003");
const pineBarkId = substrateComponentId("00000000-0000-4000-8000-000000000004");
const expectKind = (result: Promise<unknown>, kind: string) =>
  expect(result).resolves.toMatchObject({ kind });
const expectResult = (result: Promise<unknown>, expected: object) =>
  expect(result).resolves.toEqual(expected);

describe("HttpJournalClient", () => {
  it("should translate plant and operation responses into domain values", async () => {
    const pesticide = pesticideId("00000000-0000-4000-8001-000000000003");
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
      ]),
    ]);
    const journal = makeHttpJournalClient(fetch);
    const expectedPlants = [
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
    ];
    const expectedPesticides = new Set([pesticide]);
    const expectedOperations = [
      {
        id: operationId("o1"),
        plantId: plantId("p1"),
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
    ];

    await expect(journal.getPlants()).resolves.toEqual({ kind: "read", plants: expectedPlants });
    await expect(journal.getOperations(plantId("p1"))).resolves.toEqual({
      kind: "read",
      operations: expectedOperations,
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
    const expectedComponents = [
      {
        id: perliteId,
        data: { name: nomenclatureName("Perlite"), maybeInfo: nomenclatureInfo("Adds drainage") },
      },
    ];
    const expectedPesticides = [
      {
        id: pesticideId("00000000-0000-4000-8001-000000000003"),
        data: { name: nomenclatureName("Neem oil"), pesticideType: "insecticide", maybeInfo: null },
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
    const care: OperationDetails = {
      kind: "care",
      actions: new Set(["watered"]),
      pesticides: new Set([pesticideId("00000000-0000-4000-8001-000000000003")]),
      moisture: "wet",
      maybeNote: null,
    };
    const repot: OperationDetails = {
      kind: "repot",
      substrate: substrate([{ component: perliteId, share: percentage(80) }]),
      maybeNote: note("Fresh"),
    };
    const expectedOperation = {
      details: {
        kind: "repot",
        substrate: substrate([{ component: perliteId, share: percentage(80) }]),
        maybeNote: note("Fresh"),
      },
    };

    await expect(journal.logOperation(plantId("p1"), care)).resolves.toEqual({
      kind: "logged",
      id: operationId("logged"),
    });
    await expect(journal.editOperation(operationId("o1"), repot)).resolves.toEqual({
      kind: "operationTypeMismatch",
    });
    await expect(journal.editOperation(operationId("o1"), repot)).resolves.toMatchObject({
      kind: "edited",
      operation: expectedOperation,
    });
    await expect(journal.editOperation(operationId("o1"), repot)).resolves.toMatchObject({
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
    await expect(requests[0]!.json()).resolves.toEqual({
      kind: "care",
      actions: ["watered"],
      pesticides: ["00000000-0000-4000-8001-000000000003"],
      moisture: "wet",
      notes: null,
    });
    await expect(requests[1]!.json()).resolves.toEqual({
      kind: "repot",
      substrate: [{ componentId: perliteId, share: 80 }],
      notes: "Fresh",
    });
  });

  it("should send substrate component and pesticide catalog changes", async () => {
    const requests: Request[] = [];
    const pesticide = pesticideId("00000000-0000-4000-8001-000000000003");
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
        name: nomenclatureName("Perlite"),
        maybeInfo: nomenclatureInfo("Adds drainage"),
      }),
    ).resolves.toMatchObject({ kind: "added", entry: { id: perliteId } });
    await expect(
      journal.editSubstrateComponent(perliteId, {
        name: nomenclatureName("Perlite fine"),
        maybeInfo: null,
      }),
    ).resolves.toMatchObject({
      kind: "edited",
      entry: { data: { name: "Perlite fine", maybeInfo: null } },
    });
    await expect(
      journal.addPesticide({
        name: nomenclatureName("Neem oil"),
        pesticideType: "insecticide",
        maybeInfo: nomenclatureInfo("Dilute first"),
      }),
    ).resolves.toMatchObject({ kind: "added", entry: { id: pesticide } });
    await expect(
      journal.editPesticide(pesticide, {
        name: nomenclatureName("Neem"),
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
    await expect(requests[0]!.json()).resolves.toEqual({ name: "Perlite", info: "Adds drainage" });
    await expect(requests[1]!.json()).resolves.toEqual({ name: "Perlite fine", info: null });
    await expect(requests[2]!.json()).resolves.toEqual({
      name: "Neem oil",
      type: "insecticide",
      info: "Dilute first",
    });
    await expect(requests[3]!.json()).resolves.toEqual({
      name: "Neem",
      type: "insecticide",
      info: null,
    });
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
        jsonResponse({ message: "journal could not be read" }, 500),
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
    const component = substrateComponentId("00000000-0000-4000-8000-000000000003");
    const pesticide = pesticideId("00000000-0000-4000-8001-000000000003");
    const componentData = { name: nomenclatureName("Perlite"), maybeInfo: null };
    const pesticideData = {
      name: nomenclatureName("Neem"),
      pesticideType: "insecticide" as const,
      maybeInfo: null,
    };

    await expectKind(httpFailures.getPlants(), "readFailed");
    await expectKind(httpFailures.getOperations(plantId("p1")), "readFailed");
    await expectKind(httpFailures.logOperation(plantId("p1"), details), "loggingFailed");
    await expectResult(httpFailures.editOperation(operationId("missing"), details), {
      kind: "operationMissing",
    });
    await expectKind(httpFailures.editOperation(operationId("o1"), details), "editFailed");
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

    await expectNetworkFailure(networkFailures.getPlants(), "readFailed");
    await expectNetworkFailure(networkFailures.getOperations(plantId("p1")), "readFailed");
    await expectNetworkFailure(
      networkFailures.logOperation(plantId("p1"), details),
      "loggingFailed",
    );
    await expectNetworkFailure(
      networkFailures.editOperation(operationId("o1"), details),
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
