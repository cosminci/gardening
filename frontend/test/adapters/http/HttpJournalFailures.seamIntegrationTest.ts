import { describe, expect, it } from "vitest";
import { makeHttpJournalClient } from "../../../src/adapters/http/HttpJournalClient";
import {
  nomenclatureName,
  operationId,
  pesticideId,
  plantId,
  substrateComponentId,
} from "../../../src/domain/Journal";
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
    await expect(httpFailures.getSubstrateComponents()).resolves.toMatchObject({
      kind: "readFailed",
    });
    await expect(httpFailures.addSubstrateComponent(componentData)).resolves.toMatchObject({
      kind: "addFailed",
    });
    await expect(httpFailures.editSubstrateComponent(component, componentData)).resolves.toEqual({
      kind: "recordMissing",
    });
    await expect(
      httpFailures.editSubstrateComponent(component, componentData),
    ).resolves.toMatchObject({ kind: "editFailed" });
    await expect(httpFailures.getPesticides()).resolves.toMatchObject({
      kind: "readFailed",
    });
    await expect(httpFailures.addPesticide(pesticideData)).resolves.toMatchObject({
      kind: "addFailed",
    });
    await expect(httpFailures.editPesticide(pesticide, pesticideData)).resolves.toEqual({
      kind: "recordMissing",
    });
    await expect(httpFailures.editPesticide(pesticide, pesticideData)).resolves.toMatchObject({
      kind: "editFailed",
    });

    const reason = new Error("offline");
    const networkFailures = makeHttpJournalClient(
      respondingWith([
        reason,
        reason,
        reason,
        reason,
        reason,
        reason,
        reason,
        reason,
        reason,
        reason,
      ]),
    );
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
    await expect(networkFailures.getSubstrateComponents()).resolves.toEqual({
      kind: "readFailed",
      reason,
    });
    await expect(networkFailures.addSubstrateComponent(componentData)).resolves.toEqual({
      kind: "addFailed",
      reason,
    });
    await expect(networkFailures.editSubstrateComponent(component, componentData)).resolves.toEqual(
      { kind: "editFailed", reason },
    );
    await expect(networkFailures.getPesticides()).resolves.toEqual({
      kind: "readFailed",
      reason,
    });
    await expect(networkFailures.addPesticide(pesticideData)).resolves.toEqual({
      kind: "addFailed",
      reason,
    });
    await expect(networkFailures.editPesticide(pesticide, pesticideData)).resolves.toEqual({
      kind: "editFailed",
      reason,
    });
  });
});
