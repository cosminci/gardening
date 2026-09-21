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
  pesticideType,
  plantId,
  substrate,
  substrateComponentId,
} from "../../../src/domain/Journal";
import { jsonResponse, respondingWith } from "./HttpTestSupport";

const perliteId = substrateComponentId("00000000-0000-4000-8000-000000000003");

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
          substrate: substrate([{ component: perliteId, share: percentage(80) }]),
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
            data: { name: "Neem oil", type: "organic", info: "Dilute first" },
          },
          201,
        ),
        jsonResponse({
          id: pesticide,
          data: { name: "Neem", type: "organic", info: null },
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
        pesticideType: pesticideType("organic"),
        maybeInfo: nomenclatureInfo("Dilute first"),
      }),
    ).resolves.toMatchObject({ kind: "added", entry: { id: pesticide } });
    await expect(
      journal.editPesticide(pesticide, {
        name: nomenclatureName("Neem"),
        pesticideType: pesticideType("organic"),
        maybeInfo: null,
      }),
    ).resolves.toMatchObject({
      kind: "edited",
      entry: { data: { name: "Neem", pesticideType: "organic", maybeInfo: null } },
    });

    expect(requests.map((request) => `${request.method} ${new URL(request.url).pathname}`)).toEqual(
      [
        "POST /substrate-components",
        `PUT /substrate-components/${perliteId}`,
        "POST /pesticides",
        `PUT /pesticides/${pesticide}`,
      ],
    );
    await expect(requests[0]!.json()).resolves.toEqual({
      name: "Perlite",
      info: "Adds drainage",
    });
    await expect(requests[1]!.json()).resolves.toEqual({
      name: "Perlite fine",
      info: null,
    });
    await expect(requests[2]!.json()).resolves.toEqual({
      name: "Neem oil",
      type: "organic",
      info: "Dilute first",
    });
    await expect(requests[3]!.json()).resolves.toEqual({
      name: "Neem",
      type: "organic",
      info: null,
    });
  });
});
