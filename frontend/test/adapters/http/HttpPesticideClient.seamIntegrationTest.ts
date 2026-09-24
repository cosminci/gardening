import { describe, expect, it } from "vitest";
import { makeHttpPesticideClient } from "../../../src/adapters/http/HttpPesticideClient";
import * as Journal from "../../../src/domain/Journal";
import { jsonResponse, respondingWith } from "./HttpTestSupport";

const pesticideId = Journal.pesticideId("00000000-0000-4000-8001-000000000003");
const neem = {
  id: pesticideId,
  data: {
    name: Journal.nomenclatureName("Neem oil"),
    pesticideType: "insecticide" as const,
    maybeInfo: Journal.nomenclatureInfo("Dilute first"),
  },
};

describe("HttpPesticideClient", () => {
  it("should read the pesticide catalog", async () => {
    const requests: Request[] = [];
    const client = makeHttpPesticideClient(
      respondingWith(
        [
          jsonResponse([
            {
              id: pesticideId,
              data: { name: "Neem oil", type: "insecticide", info: "Dilute first" },
            },
          ]),
        ],
        requests,
      ),
    );

    const result = await client.getPesticides();
    const requestedPaths = requests.map(
      (request) => `${request.method} ${new URL(request.url).pathname}`,
    );

    expect(result).toEqual({ kind: "read", entries: [neem] });
    expect(requestedPaths).toEqual(["GET /pesticides"]);
  });

  it("should add and edit pesticides with unchanged wire bodies", async () => {
    const requests: Request[] = [];
    const client = makeHttpPesticideClient(
      respondingWith(
        [
          jsonResponse(
            {
              id: pesticideId,
              data: { name: "Neem oil", type: "insecticide", info: "Dilute first" },
            },
            201,
          ),
          jsonResponse({ id: pesticideId, data: { name: "Neem", type: "treatment", info: null } }),
        ],
        requests,
      ),
    );
    const editedData = {
      name: Journal.nomenclatureName("Neem"),
      pesticideType: "treatment" as const,
      maybeInfo: null,
    };

    const added = await client.addPesticide(neem.data);
    const edited = await client.editPesticide(pesticideId, editedData);
    const requestedPaths = requests.map(
      (request) => `${request.method} ${new URL(request.url).pathname}`,
    );
    const requestBodies = await Promise.all(requests.map((request) => request.json()));

    expect(added).toEqual({ kind: "added", entry: neem });
    expect(edited).toEqual({ kind: "edited", entry: { id: pesticideId, data: editedData } });
    expect(requestedPaths).toEqual(["POST /pesticides", `PUT /pesticides/${pesticideId}`]);
    expect(requestBodies).toEqual([
      { name: "Neem oil", type: "insecticide", info: "Dilute first" },
      { name: "Neem", type: "treatment", info: null },
    ]);
  });

  it("should distinguish missing records from catalog failures", async () => {
    const client = makeHttpPesticideClient(
      respondingWith([
        jsonResponse({ message: "nomenclatures could not be read" }, 500),
        jsonResponse({ message: "nomenclature could not be saved" }, 500),
        jsonResponse({ message: "nomenclature not found" }, 404),
        jsonResponse({ message: "nomenclature could not be saved" }, 500),
      ]),
    );

    const read = await client.getPesticides();
    const added = await client.addPesticide(neem.data);
    const missing = await client.editPesticide(pesticideId, neem.data);
    const failed = await client.editPesticide(pesticideId, neem.data);

    expect(read.kind).toBe("readFailed");
    expect(added.kind).toBe("addFailed");
    expect(missing).toEqual({ kind: "recordMissing" });
    expect(failed.kind).toBe("editFailed");
  });

  it("should preserve network failure classifications", async () => {
    const reason = new Error("offline");
    const client = makeHttpPesticideClient(respondingWith(new Array<Error>(3).fill(reason)));

    const read = await client.getPesticides();
    const added = await client.addPesticide(neem.data);
    const edited = await client.editPesticide(pesticideId, neem.data);

    expect(read).toEqual({ kind: "readFailed", reason });
    expect(added).toEqual({ kind: "addFailed", reason });
    expect(edited).toEqual({ kind: "editFailed", reason });
  });
});
