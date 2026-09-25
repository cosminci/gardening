import { describe, expect, it } from "vitest";
import { makeHttpPesticideClient } from "../../../src/adapters/http/HttpPesticideClient";
import * as Journal from "../../../src/domain/Journal";
import { jsonResponse, respondingWith } from "./HttpTestSupport";

const pesticideId = Journal.pesticideId("00000000-0000-4000-8001-000000000003");
const neem = {
  id: pesticideId,
  data: {
    name: Journal.pesticideName("Neem oil"),
    pesticideType: "insecticide" as const,
    maybeInfo: Journal.pesticideInfo("Dilute first"),
  },
  status: "active" as const,
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
              status: "active",
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
              status: "active",
            },
            201,
          ),
          jsonResponse({
            id: pesticideId,
            data: { name: "Neem", type: "treatment", info: null },
            status: "active",
          }),
        ],
        requests,
      ),
    );
    const editedData = {
      name: Journal.pesticideName("Neem"),
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
    expect(edited).toEqual({
      kind: "edited",
      entry: { id: pesticideId, data: editedData, status: "active" },
    });
    expect(requestedPaths).toEqual(["POST /pesticides", `PUT /pesticides/${pesticideId}`]);
    expect(requestBodies).toEqual([
      { name: "Neem oil", type: "insecticide", info: "Dilute first" },
      { name: "Neem", type: "treatment", info: null },
    ]);
  });

  it("should archive a pesticide and distinguish an unknown or already-archived one", async () => {
    const requests: Request[] = [];
    const client = makeHttpPesticideClient(
      respondingWith(
        [
          jsonResponse({
            id: pesticideId,
            data: { name: "Neem oil", type: "insecticide", info: "Dilute first" },
            status: "archived",
          }),
          jsonResponse({ message: "pesticide not found" }, 404),
          jsonResponse({ message: "pesticide is already archived" }, 409),
        ],
        requests,
      ),
    );

    const archived = await client.archivePesticide(pesticideId);
    const missing = await client.archivePesticide(pesticideId);
    const alreadyArchived = await client.archivePesticide(pesticideId);
    const requestedPaths = requests.map(
      (request) => `${request.method} ${new URL(request.url).pathname}`,
    );

    expect(archived).toEqual({ kind: "archived", entry: { ...neem, status: "archived" } });
    expect(missing).toEqual({ kind: "pesticideMissing" });
    expect(alreadyArchived).toEqual({ kind: "alreadyArchived" });
    expect(requestedPaths).toEqual([
      `POST /pesticides/${pesticideId}/archive`,
      `POST /pesticides/${pesticideId}/archive`,
      `POST /pesticides/${pesticideId}/archive`,
    ]);
  });

  it("should distinguish missing or archived records from catalog failures", async () => {
    const client = makeHttpPesticideClient(
      respondingWith([
        jsonResponse({ message: "pesticides could not be read" }, 500),
        jsonResponse({ message: "pesticide could not be saved" }, 500),
        jsonResponse({ message: "pesticide not found" }, 404),
        jsonResponse({ message: "pesticide is archived" }, 409),
        jsonResponse({ message: "pesticide could not be saved" }, 500),
        jsonResponse({ message: "pesticide could not be archived" }, 500),
      ]),
    );

    const read = await client.getPesticides();
    const added = await client.addPesticide(neem.data);
    const missing = await client.editPesticide(pesticideId, neem.data);
    const archived = await client.editPesticide(pesticideId, neem.data);
    const failed = await client.editPesticide(pesticideId, neem.data);
    const archiveFailed = await client.archivePesticide(pesticideId);

    expect(read.kind).toBe("readFailed");
    expect(added.kind).toBe("addFailed");
    expect(missing).toEqual({ kind: "pesticideMissing" });
    expect(archived).toEqual({ kind: "pesticideArchived" });
    expect(failed.kind).toBe("editFailed");
    expect(archiveFailed.kind).toBe("archiveFailed");
  });

  it("should preserve network failure classifications", async () => {
    const reason = new Error("offline");
    const client = makeHttpPesticideClient(respondingWith(new Array<Error>(4).fill(reason)));

    const read = await client.getPesticides();
    const added = await client.addPesticide(neem.data);
    const edited = await client.editPesticide(pesticideId, neem.data);
    const archived = await client.archivePesticide(pesticideId);

    expect(read).toEqual({ kind: "readFailed", reason });
    expect(added).toEqual({ kind: "addFailed", reason });
    expect(edited).toEqual({ kind: "editFailed", reason });
    expect(archived).toEqual({ kind: "archiveFailed", reason });
  });
});
