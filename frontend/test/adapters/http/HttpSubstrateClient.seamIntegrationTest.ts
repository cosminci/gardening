import { describe, expect, it } from "vitest";
import { makeHttpSubstrateClient } from "../../../src/adapters/http/HttpSubstrateClient";
import * as Journal from "../../../src/domain/Journal";
import { jsonResponse, respondingWith } from "./HttpTestSupport";

const perliteId = Journal.substrateComponentId("00000000-0000-4000-8000-000000000003");
const perlite = {
  id: perliteId,
  data: {
    name: Journal.substrateComponentName("Perlite"),
    maybeInfo: Journal.substrateComponentInfo("Adds drainage"),
  },
  status: "active" as const,
};

const mixId = Journal.substrateMixId("00000000-0000-4000-8000-000000000004");
const mixSubstrate = Journal.substrate([{ component: perliteId, share: Journal.percentage(100) }]);
const mix = {
  id: mixId,
  name: Journal.substrateMixName("Standard mix"),
  maybeNotes: Journal.substrateMixNotes("Works well for aroids"),
  substrate: mixSubstrate,
};

describe("HttpSubstrateClient", () => {
  it("should read substrate components at their resource path", async () => {
    const requests: Request[] = [];
    const client = makeHttpSubstrateClient(
      respondingWith(
        [
          jsonResponse([
            {
              id: perliteId,
              data: { name: "Perlite", info: "Adds drainage" },
              status: "active",
            },
          ]),
        ],
        requests,
      ),
    );

    const result = await client.getSubstrateComponents();
    const requestedPaths = requests.map(
      (request) => `${request.method} ${new URL(request.url).pathname}`,
    );

    expect(result).toEqual({ kind: "read", entries: [perlite] });
    expect(requestedPaths).toEqual(["GET /substrates/components"]);
  });

  it("should add and edit substrate components with the same wire representations", async () => {
    const requests: Request[] = [];
    const client = makeHttpSubstrateClient(
      respondingWith(
        [
          jsonResponse(
            { id: perliteId, data: { name: "Perlite", info: "Adds drainage" }, status: "active" },
            201,
          ),
          jsonResponse({
            id: perliteId,
            data: { name: "Fine perlite", info: null },
            status: "active",
          }),
        ],
        requests,
      ),
    );
    const editedData = { name: Journal.substrateComponentName("Fine perlite"), maybeInfo: null };

    const added = await client.addSubstrateComponent(perlite.data);
    const edited = await client.editSubstrateComponent(perliteId, editedData);
    const requestedPaths = requests.map(
      (request) => `${request.method} ${new URL(request.url).pathname}`,
    );
    const requestBodies = await Promise.all(requests.map((request) => request.json()));

    expect(added).toEqual({ kind: "added", entry: perlite });
    expect(edited).toEqual({
      kind: "edited",
      entry: { id: perliteId, data: editedData, status: "active" },
    });
    expect(requestedPaths).toEqual([
      "POST /substrates/components",
      `PUT /substrates/components/${perliteId}`,
    ]);
    expect(requestBodies).toEqual([
      { name: "Perlite", info: "Adds drainage" },
      { name: "Fine perlite", info: null },
    ]);
  });

  it("should archive a substrate component and distinguish an unknown or already-archived one", async () => {
    const requests: Request[] = [];
    const client = makeHttpSubstrateClient(
      respondingWith(
        [
          jsonResponse({
            id: perliteId,
            data: { name: "Perlite", info: "Adds drainage" },
            status: "archived",
          }),
          jsonResponse({ message: "substrate component not found" }, 404),
          jsonResponse({ message: "substrate component is already archived" }, 409),
        ],
        requests,
      ),
    );

    const archived = await client.archiveSubstrateComponent(perliteId);
    const missing = await client.archiveSubstrateComponent(perliteId);
    const alreadyArchived = await client.archiveSubstrateComponent(perliteId);
    const requestedPaths = requests.map(
      (request) => `${request.method} ${new URL(request.url).pathname}`,
    );

    expect(archived).toEqual({ kind: "archived", entry: { ...perlite, status: "archived" } });
    expect(missing).toEqual({ kind: "componentMissing" });
    expect(alreadyArchived).toEqual({ kind: "alreadyArchived" });
    expect(requestedPaths).toEqual([
      `POST /substrates/components/${perliteId}/archive`,
      `POST /substrates/components/${perliteId}/archive`,
      `POST /substrates/components/${perliteId}/archive`,
    ]);
  });

  it("should distinguish missing or archived records from catalog failures", async () => {
    const client = makeHttpSubstrateClient(
      respondingWith([
        jsonResponse({ message: "substrate components could not be read" }, 500),
        jsonResponse({ message: "substrate component could not be saved" }, 500),
        jsonResponse({ message: "substrate component not found" }, 404),
        jsonResponse({ message: "substrate component is archived" }, 409),
        jsonResponse({ message: "substrate component could not be saved" }, 500),
        jsonResponse({ message: "substrate component could not be archived" }, 500),
      ]),
    );

    const read = await client.getSubstrateComponents();
    const added = await client.addSubstrateComponent(perlite.data);
    const missing = await client.editSubstrateComponent(perliteId, perlite.data);
    const archived = await client.editSubstrateComponent(perliteId, perlite.data);
    const failed = await client.editSubstrateComponent(perliteId, perlite.data);
    const archiveFailed = await client.archiveSubstrateComponent(perliteId);

    expect(read.kind).toBe("readFailed");
    expect(added.kind).toBe("addFailed");
    expect(missing).toEqual({ kind: "componentMissing" });
    expect(archived).toEqual({ kind: "componentArchived" });
    expect(failed.kind).toBe("editFailed");
    expect(archiveFailed.kind).toBe("archiveFailed");
  });

  it("should surface network failures without reporting a successful catalog edit", async () => {
    const reason = new Error("offline");
    const client = makeHttpSubstrateClient(respondingWith(new Array<Error>(4).fill(reason)));

    const read = await client.getSubstrateComponents();
    const added = await client.addSubstrateComponent(perlite.data);
    const edited = await client.editSubstrateComponent(perliteId, perlite.data);
    const archived = await client.archiveSubstrateComponent(perliteId);

    expect(read).toEqual({ kind: "readFailed", reason });
    expect(added).toEqual({ kind: "addFailed", reason });
    expect(edited).toEqual({ kind: "editFailed", reason });
    expect(archived).toEqual({ kind: "archiveFailed", reason });
  });

  it("should read substrate mixes at their resource path", async () => {
    const requests: Request[] = [];
    const noNotesMixId = Journal.substrateMixId("00000000-0000-4000-8000-000000000005");
    const noNotesMix = {
      id: noNotesMixId,
      name: Journal.substrateMixName("Bare mix"),
      maybeNotes: null,
      substrate: mixSubstrate,
    };
    const client = makeHttpSubstrateClient(
      respondingWith(
        [
          jsonResponse([
            {
              id: mixId,
              name: "Standard mix",
              notes: "Works well for aroids",
              substrate: [{ componentId: perliteId, share: 100 }],
            },
            {
              id: noNotesMixId,
              name: "Bare mix",
              notes: null,
              substrate: [{ componentId: perliteId, share: 100 }],
            },
          ]),
        ],
        requests,
      ),
    );

    const result = await client.getSubstrateMixes();
    const requestedPaths = requests.map(
      (request) => `${request.method} ${new URL(request.url).pathname}`,
    );

    expect(result).toEqual({ kind: "read", entries: [mix, noNotesMix] });
    expect(requestedPaths).toEqual(["GET /substrates/mixes"]);
  });

  it("should add a substrate mix with the wire componentId field", async () => {
    const requests: Request[] = [];
    const client = makeHttpSubstrateClient(
      respondingWith(
        [
          jsonResponse(
            {
              id: mixId,
              name: "Standard mix",
              notes: "Works well for aroids",
              substrate: [{ componentId: perliteId, share: 100 }],
            },
            201,
          ),
        ],
        requests,
      ),
    );

    const added = await client.addSubstrateMix(mix.name, mix.maybeNotes, mixSubstrate);
    const requestedPaths = requests.map(
      (request) => `${request.method} ${new URL(request.url).pathname}`,
    );
    const requestBodies = await Promise.all(requests.map((request) => request.json()));

    expect(added).toEqual({ kind: "added", entry: mix });
    expect(requestedPaths).toEqual(["POST /substrates/mixes"]);
    expect(requestBodies).toEqual([
      {
        name: "Standard mix",
        notes: "Works well for aroids",
        substrate: [{ componentId: perliteId, share: 100 }],
      },
    ]);
  });

  it("should treat a duplicate substrate mix conflict as a domain outcome", async () => {
    const client = makeHttpSubstrateClient(
      respondingWith([
        jsonResponse({ message: "a substrate mix with these components already exists" }, 409),
      ]),
    );

    const added = await client.addSubstrateMix(mix.name, mix.maybeNotes, mixSubstrate);

    expect(added).toEqual({ kind: "duplicateSubstrate" });
  });

  it("should retain storage failure outcomes for substrate mixes", async () => {
    const client = makeHttpSubstrateClient(
      respondingWith([
        jsonResponse({ message: "substrate mixes could not be read" }, 500),
        jsonResponse({ message: "substrate mix could not be saved" }, 500),
        jsonResponse({ message: "substrate mix could not be deleted" }, 500),
      ]),
    );

    const read = await client.getSubstrateMixes();
    const added = await client.addSubstrateMix(mix.name, mix.maybeNotes, mixSubstrate);
    const deleted = await client.deleteSubstrateMix(mixId);

    expect(read.kind).toBe("readFailed");
    expect(added.kind).toBe("addFailed");
    expect(deleted.kind).toBe("deleteFailed");
  });

  it("should delete a substrate mix at its resource path", async () => {
    const requests: Request[] = [];
    const client = makeHttpSubstrateClient(
      respondingWith([new Response(null, { status: 204 })], requests),
    );

    const result = await client.deleteSubstrateMix(mixId);
    const requestedPaths = requests.map(
      (request) => `${request.method} ${new URL(request.url).pathname}`,
    );

    expect(result).toEqual({ kind: "deleted" });
    expect(requestedPaths).toEqual([`DELETE /substrates/mixes/${mixId}`]);
  });

  it("should surface network failures for substrate mix operations", async () => {
    const reason = new Error("offline");
    const client = makeHttpSubstrateClient(respondingWith(new Array<Error>(3).fill(reason)));

    const read = await client.getSubstrateMixes();
    const added = await client.addSubstrateMix(mix.name, mix.maybeNotes, mixSubstrate);
    const deleted = await client.deleteSubstrateMix(mixId);

    expect(read).toEqual({ kind: "readFailed", reason });
    expect(added).toEqual({ kind: "addFailed", reason });
    expect(deleted).toEqual({ kind: "deleteFailed", reason });
  });
});
