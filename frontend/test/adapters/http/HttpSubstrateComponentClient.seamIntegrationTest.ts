import { describe, expect, it } from "vitest";
import { makeHttpSubstrateComponentClient } from "../../../src/adapters/http/HttpSubstrateComponentClient";
import * as Journal from "../../../src/domain/Journal";
import { jsonResponse, respondingWith } from "./HttpTestSupport";

const perliteId = Journal.substrateComponentId("00000000-0000-4000-8000-000000000003");
const perlite = {
  id: perliteId,
  data: {
    name: Journal.nomenclatureName("Perlite"),
    maybeInfo: Journal.nomenclatureInfo("Adds drainage"),
  },
};

describe("HttpSubstrateComponentClient", () => {
  it("should read substrate components at their resource path", async () => {
    const requests: Request[] = [];
    const client = makeHttpSubstrateComponentClient(
      respondingWith(
        [jsonResponse([{ id: perliteId, data: { name: "Perlite", info: "Adds drainage" } }])],
        requests,
      ),
    );

    const result = await client.getSubstrateComponents();
    const requestedPaths = requests.map(
      (request) => `${request.method} ${new URL(request.url).pathname}`,
    );

    expect(result).toEqual({ kind: "read", entries: [perlite] });
    expect(requestedPaths).toEqual(["GET /substrate/components"]);
  });

  it("should add and edit substrate components with the same wire representations", async () => {
    const requests: Request[] = [];
    const client = makeHttpSubstrateComponentClient(
      respondingWith(
        [
          jsonResponse({ id: perliteId, data: { name: "Perlite", info: "Adds drainage" } }, 201),
          jsonResponse({ id: perliteId, data: { name: "Fine perlite", info: null } }),
        ],
        requests,
      ),
    );
    const editedData = { name: Journal.nomenclatureName("Fine perlite"), maybeInfo: null };

    const added = await client.addSubstrateComponent(perlite.data);
    const edited = await client.editSubstrateComponent(perliteId, editedData);
    const requestedPaths = requests.map(
      (request) => `${request.method} ${new URL(request.url).pathname}`,
    );
    const requestBodies = await Promise.all(requests.map((request) => request.json()));

    expect(added).toEqual({ kind: "added", entry: perlite });
    expect(edited).toEqual({ kind: "edited", entry: { id: perliteId, data: editedData } });
    expect(requestedPaths).toEqual([
      "POST /substrate/components",
      `PUT /substrate/components/${perliteId}`,
    ]);
    expect(requestBodies).toEqual([
      { name: "Perlite", info: "Adds drainage" },
      { name: "Fine perlite", info: null },
    ]);
  });

  it("should retain missing-record and storage failure outcomes", async () => {
    const client = makeHttpSubstrateComponentClient(
      respondingWith([
        jsonResponse({ message: "nomenclatures could not be read" }, 500),
        jsonResponse({ message: "nomenclature could not be saved" }, 500),
        jsonResponse({ message: "nomenclature not found" }, 404),
        jsonResponse({ message: "nomenclature could not be saved" }, 500),
      ]),
    );

    const read = await client.getSubstrateComponents();
    const added = await client.addSubstrateComponent(perlite.data);
    const missing = await client.editSubstrateComponent(perliteId, perlite.data);
    const failed = await client.editSubstrateComponent(perliteId, perlite.data);

    expect(read.kind).toBe("readFailed");
    expect(added.kind).toBe("addFailed");
    expect(missing).toEqual({ kind: "recordMissing" });
    expect(failed.kind).toBe("editFailed");
  });

  it("should surface network failures without reporting a successful catalog edit", async () => {
    const reason = new Error("offline");
    const client = makeHttpSubstrateComponentClient(
      respondingWith(new Array<Error>(3).fill(reason)),
    );

    const read = await client.getSubstrateComponents();
    const added = await client.addSubstrateComponent(perlite.data);
    const edited = await client.editSubstrateComponent(perliteId, perlite.data);

    expect(read).toEqual({ kind: "readFailed", reason });
    expect(added).toEqual({ kind: "addFailed", reason });
    expect(edited).toEqual({ kind: "editFailed", reason });
  });
});
