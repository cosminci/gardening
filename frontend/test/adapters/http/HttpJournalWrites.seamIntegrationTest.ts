import { describe, expect, it } from "vitest";
import { makeHttpJournalClient } from "../../../src/adapters/http/HttpJournalClient";
import type { OperationDetails } from "../../../src/domain/Journal";
import { note, operationId, percentage, plantId, substrate } from "../../../src/domain/Journal";
import { jsonResponse, respondingWith } from "./HttpTestSupport";

describe("HttpJournalClient writes", () => {
  it("should send operation details and preserve write outcomes", async () => {
    const requests: Request[] = [];
    const repotResponse = (notes: string | null) =>
      jsonResponse({
        id: "o1",
        plantId: "p1",
        date: "2026-01-01T00:00:00Z",
        details: {
          kind: "repot",
          substrate: [{ component: "perlite", share: 80 }],
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
      moisture: "wet",
      maybeNote: null,
    };
    const repot: OperationDetails = {
      kind: "repot",
      substrate: substrate([{ component: "perlite", share: percentage(80) }]),
      maybeNote: note("Fresh"),
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
      operation: {
        details: {
          kind: "repot",
          substrate: substrate([{ component: "perlite", share: percentage(80) }]),
          maybeNote: note("Fresh"),
        },
      },
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
      moisture: "wet",
      notes: null,
    });
    await expect(requests[1]!.json()).resolves.toEqual({
      kind: "repot",
      substrate: [{ component: "perlite", share: 80 }],
      notes: "Fresh",
    });
  });
});
