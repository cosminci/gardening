import { describe, expect, it } from "vitest";
import { makeHttpJournalClient } from "../../../src/adapters/http/HttpJournalClient";
import { operationId, plantId } from "../../../src/domain/Journal";
import { jsonResponse, respondingWith } from "./HttpTestSupport";

describe("HttpJournalClient failures", () => {
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
      ]),
    );

    await expect(httpFailures.getPlants()).resolves.toMatchObject({ kind: "readFailed" });
    await expect(httpFailures.getOperations(plantId("p1"))).resolves.toMatchObject({
      kind: "readFailed",
    });
    await expect(httpFailures.logOperation(plantId("p1"), details)).resolves.toMatchObject({
      kind: "loggingFailed",
    });
    await expect(httpFailures.editOperation(operationId("missing"), details)).resolves.toEqual({
      kind: "operationMissing",
    });
    await expect(httpFailures.editOperation(operationId("o1"), details)).resolves.toMatchObject({
      kind: "editFailed",
    });

    const reason = new Error("offline");
    const networkFailures = makeHttpJournalClient(respondingWith([reason, reason, reason, reason]));
    await expect(networkFailures.getPlants()).resolves.toEqual({ kind: "readFailed", reason });
    await expect(networkFailures.getOperations(plantId("p1"))).resolves.toEqual({
      kind: "readFailed",
      reason,
    });
    await expect(networkFailures.logOperation(plantId("p1"), details)).resolves.toEqual({
      kind: "loggingFailed",
      reason,
    });
    await expect(networkFailures.editOperation(operationId("o1"), details)).resolves.toEqual({
      kind: "editFailed",
      reason,
    });
  });
});
