import { describe, expect, it } from "vitest";
import { makeHttpOperationClient } from "../../../src/adapters/http/HttpOperationClient";
import * as Journal from "../../../src/domain/Journal";
import { jsonResponse, respondingWith } from "./HttpTestSupport";

const perliteId = Journal.substrateComponentId("00000000-0000-4000-8000-000000000003");

describe("HttpOperationClient", () => {
  it("should read the first and last operation dates without paging through history", async () => {
    const requests: Request[] = [];
    const client = makeHttpOperationClient(
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

    const recordedResult = await client.getOperationDates(plantId);
    const emptyResult = await client.getOperationDates(plantId);
    const expectedDates = {
      kind: "recorded",
      first: Journal.instant("2026-02-01T10:00:00Z"),
      last: Journal.instant("2026-04-03T18:00:00Z"),
    };

    expect(recordedResult).toEqual({ kind: "read", dates: expectedDates });
    expect(emptyResult).toEqual({ kind: "read", dates: { kind: "empty" } });
    const urls = requests.map(
      (request) => `${new URL(request.url).pathname}${new URL(request.url).search}`,
    );
    expect(urls).toEqual([
      "/operations/date-range?plantId=p1",
      "/operations/date-range?plantId=p1",
    ]);
  });

  it("should reject incomplete or unavailable recorded operation dates", async () => {
    const client = makeHttpOperationClient(
      respondingWith([
        jsonResponse({ kind: "recorded", first: "2026-02-01T10:00:00Z", last: null }),
        jsonResponse({ message: "missing plant" }, 404),
        new Error("offline"),
      ]),
    );
    const plantId = Journal.plantId("p1");

    const incompleteDates = await client.getOperationDates(plantId);
    const missingDates = await client.getOperationDates(plantId);
    const offlineDates = await client.getOperationDates(plantId);

    expect(incompleteDates).toMatchObject({ kind: "readFailed" });
    expect(missingDates).toMatchObject({ kind: "readFailed" });
    expect(offlineDates).toMatchObject({ kind: "readFailed" });
  });

  it("should reject logging an operation after its plant has been archived", async () => {
    const client = makeHttpOperationClient(
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

    const result = await client.logOperation(plantId, date, care);

    expect(result).toEqual({ kind: "plantArchived" });
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
    const client = makeHttpOperationClient(fetch);
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

    const operationsResult = await client.getOperations(Journal.plantId("p1"), {
      offset: 0,
      size: 3,
    });
    const expectedResult = {
      kind: "read",
      page: { operations: expectedOperations, hasNextPage: true },
    };
    expect(operationsResult).toEqual(expectedResult);
    expect(requests[0]?.url).toContain("/operations?plantId=p1&offset=0&pageSize=3");
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
    const client = makeHttpOperationClient(fetch);
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

    const loggedResult = await client.logOperation(
      Journal.plantId("p1"),
      Journal.instant("2026-01-01T00:00:00Z"),
      care,
    );
    const mismatchResult = await client.editOperation(Journal.operationId("o1"), repot);
    const editedResult = await client.editOperation(Journal.operationId("o1"), repot);
    const clearedResult = await client.editOperation(Journal.operationId("o1"), repot);
    const requestedPaths = requests.map(
      (request) => `${request.method} ${new URL(request.url).pathname}`,
    );
    const actualRequests = await Promise.all(requests.map((request) => request.json()));

    const expectedLogged = { kind: "logged", id: Journal.operationId("logged") };
    const expectedPaths = [
      "POST /operations",
      "PUT /operations/o1",
      "PUT /operations/o1",
      "PUT /operations/o1",
    ];
    const expectedRequests = [
      {
        plantId: "p1",
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
    const httpFailures = makeHttpOperationClient(
      respondingWith([
        jsonResponse({ message: "journal could not be read" }, 500),
        jsonResponse("invalid body", 400),
        jsonResponse({ message: "operation not found" }, 404),
        jsonResponse({ message: "journal could not be changed" }, 500),
      ]),
    );
    const reason = new Error("offline");
    const networkFailures = makeHttpOperationClient(
      respondingWith(new Array<Error>(3).fill(reason)),
    );

    const failedRead = await httpFailures.getOperations(Journal.plantId("p1"), {
      offset: 0,
      size: 3,
    });
    const failedLog = await httpFailures.logOperation(
      Journal.plantId("p1"),
      Journal.instant("2026-01-01T00:00:00Z"),
      details,
    );
    const missingOperation = await httpFailures.editOperation(
      Journal.operationId("missing"),
      details,
    );
    const failedEdit = await httpFailures.editOperation(Journal.operationId("o1"), details);
    const offlineRead = await networkFailures.getOperations(Journal.plantId("p1"), {
      offset: 0,
      size: 3,
    });
    const offlineLog = await networkFailures.logOperation(
      Journal.plantId("p1"),
      Journal.instant("2026-01-01T00:00:00Z"),
      details,
    );
    const offlineEdit = await networkFailures.editOperation(Journal.operationId("o1"), details);

    expect(failedRead).toMatchObject({ kind: "readFailed" });
    expect(failedLog).toMatchObject({ kind: "loggingFailed" });
    expect(missingOperation).toEqual({ kind: "operationMissing" });
    expect(failedEdit).toMatchObject({ kind: "editFailed" });
    expect(offlineRead).toEqual({ kind: "readFailed", reason });
    expect(offlineLog).toEqual({ kind: "loggingFailed", reason });
    expect(offlineEdit).toEqual({ kind: "editFailed", reason });
  });

  it("should delete an operation and preserve its outcomes", async () => {
    const requests: Request[] = [];
    const client = makeHttpOperationClient(
      respondingWith(
        [
          new Response(null, { status: 204 }),
          jsonResponse({ message: "operation not found" }, 404),
          jsonResponse({ message: "cannot delete the plant's current latest repot" }, 409),
          jsonResponse({ message: "operation could not be deleted" }, 500),
        ],
        requests,
      ),
    );

    const deleted = await client.deleteOperation(Journal.operationId("o1"));
    const missing = await client.deleteOperation(Journal.operationId("missing"));
    const latestRepot = await client.deleteOperation(Journal.operationId("repot"));
    const failed = await client.deleteOperation(Journal.operationId("o1"));

    expect(deleted).toEqual({ kind: "deleted" });
    expect(missing).toEqual({ kind: "operationMissing" });
    expect(latestRepot).toEqual({ kind: "cannotDeleteLatestRepot" });
    expect(failed).toMatchObject({ kind: "deleteFailed" });
    const requestedPaths = requests.map(
      (request) => `${request.method} ${new URL(request.url).pathname}`,
    );
    expect(requestedPaths).toEqual([
      "DELETE /operations/o1",
      "DELETE /operations/missing",
      "DELETE /operations/repot",
      "DELETE /operations/o1",
    ]);
  });

  it("should translate a deletion network failure into an explicit domain failure", async () => {
    const reason = new Error("offline");
    const client = makeHttpOperationClient(respondingWith([reason]));

    const offlineDelete = await client.deleteOperation(Journal.operationId("o1"));

    expect(offlineDelete).toEqual({ kind: "deleteFailed", reason });
  });
});
