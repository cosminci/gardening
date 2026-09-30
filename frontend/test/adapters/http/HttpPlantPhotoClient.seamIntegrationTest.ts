import { describe, expect, it } from "vitest";
import {
  makeHttpPlantPhotoClient,
  photoContentUrl,
} from "../../../src/adapters/http/HttpPlantPhotoClient";
import * as Journal from "../../../src/domain/Journal";
import { jsonResponse, respondingWith } from "./HttpTestSupport";

const plantId = Journal.plantId("p1");
const photoId1 = Journal.photoId("ph1");

describe("HttpPlantPhotoClient", () => {
  it("should read a page of plant photos", async () => {
    const requests: Request[] = [];
    const client = makeHttpPlantPhotoClient(
      respondingWith(
        [
          jsonResponse({
            photos: [
              { id: "ph1", capturedAt: "2026-05-15T10:00:00Z" },
              { id: "ph2", capturedAt: "2026-05-14T08:30:00Z" },
            ],
            hasNextPage: true,
          }),
        ],
        requests,
      ),
    );

    const result = await client.getPhotos(plantId, { offset: 0, size: 12 });

    const expectedPhotos = [
      { id: Journal.photoId("ph1"), capturedAt: Journal.instant("2026-05-15T10:00:00Z") },
      { id: Journal.photoId("ph2"), capturedAt: Journal.instant("2026-05-14T08:30:00Z") },
    ];
    expect(result).toEqual({
      kind: "read",
      page: { photos: expectedPhotos, hasNextPage: true },
    });
    const url = requests[0]?.url ?? "";
    expect(url).toContain("/plants/p1/photos?offset=0&pageSize=12");
  });

  it("should translate HTTP and network failures for getPhotos into explicit domain failures", async () => {
    const reason = new Error("offline");
    const httpFailure = makeHttpPlantPhotoClient(
      respondingWith([jsonResponse({ message: "server error" }, 500)]),
    );
    const networkFailure = makeHttpPlantPhotoClient(respondingWith([reason]));

    const httpResult = await httpFailure.getPhotos(plantId, { offset: 0, size: 12 });
    const networkResult = await networkFailure.getPhotos(plantId, { offset: 0, size: 12 });

    expect(httpResult).toMatchObject({ kind: "readFailed" });
    expect(networkResult).toEqual({ kind: "readFailed", reason });
  });

  it("should add a photo and return the created record", async () => {
    const requests: Request[] = [];
    const client = makeHttpPlantPhotoClient(
      respondingWith(
        [jsonResponse({ id: "ph-new", capturedAt: "2026-09-26T12:00:00Z" }, 201)],
        requests,
      ),
    );
    const file = new File(["jpeg"], "photo.jpg", { type: "image/jpeg" });

    const result = await client.addPhoto(plantId, file);

    expect(result).toEqual({
      kind: "added",
      photo: {
        id: Journal.photoId("ph-new"),
        capturedAt: Journal.instant("2026-09-26T12:00:00Z"),
      },
    });
    const [request] = requests;
    if (request === undefined) throw new Error("no request made");
    expect(request.method).toBe("POST");
    expect(request.url).toContain("/plants/p1/photos");
    // jsdom's Request/FormData polyfill doesn't serialize a real multipart body the way browsers
    // and Node's undici do, so the exact wire shape isn't asserted here - per this change's testing
    // Doc Sync, file upload is validated end-to-end, not through this isolated adapter seam.
    expect(request.body).not.toBeNull();
  });

  it("should add a photo even where crypto.randomUUID is unavailable, as on an insecure origin", async () => {
    const originalRandomUUID = crypto.randomUUID.bind(crypto);
    // crypto.randomUUID is restricted to secure contexts (HTTPS or localhost); this app is also
    // used over plain HTTP on the local network, where a browser leaves it undefined.
    // @ts-expect-error -- simulating an insecure context, where this member doesn't exist
    delete crypto.randomUUID;
    try {
      const client = makeHttpPlantPhotoClient(
        respondingWith([jsonResponse({ id: "ph-new", capturedAt: "2026-09-26T12:00:00Z" }, 201)]),
      );
      const file = new File(["jpeg"], "photo.jpg", { type: "image/jpeg" });

      const result = await client.addPhoto(plantId, file);

      expect(result).toEqual({
        kind: "added",
        photo: {
          id: Journal.photoId("ph-new"),
          capturedAt: Journal.instant("2026-09-26T12:00:00Z"),
        },
      });
    } finally {
      crypto.randomUUID = originalRandomUUID;
    }
  });

  it("should translate photo upload failures into explicit domain results", async () => {
    const reason = new Error("offline");
    const client = makeHttpPlantPhotoClient(
      respondingWith([
        jsonResponse({ message: "plant not found" }, 404),
        jsonResponse({ message: "too large" }, 413),
        jsonResponse({ message: "unsupported media type" }, 415),
        jsonResponse({ message: "server error" }, 500),
      ]),
    );
    const networkClient = makeHttpPlantPhotoClient(respondingWith([reason]));
    const file = new File(["data"], "p.jpg", { type: "image/jpeg" });

    const plantMissing = await client.addPhoto(plantId, file);
    const tooLarge = await client.addPhoto(plantId, file);
    const unsupported = await client.addPhoto(plantId, file);
    const failed = await client.addPhoto(plantId, file);
    const offline = await networkClient.addPhoto(plantId, file);

    expect(plantMissing).toEqual({ kind: "plantMissing" });
    expect(tooLarge).toEqual({ kind: "tooLarge" });
    expect(unsupported).toEqual({ kind: "unsupportedMediaType" });
    expect(failed).toMatchObject({ kind: "addFailed" });
    expect(offline).toEqual({ kind: "addFailed", reason });
  });

  it("should remove a photo and preserve all outcomes", async () => {
    const requests: Request[] = [];
    const reason = new Error("offline");
    const client = makeHttpPlantPhotoClient(
      respondingWith(
        [
          new Response(null, { status: 204 }),
          jsonResponse({ message: "photo not found" }, 404),
          jsonResponse({ message: "server error" }, 500),
        ],
        requests,
      ),
    );
    const offlineClient = makeHttpPlantPhotoClient(respondingWith([reason]));

    const removed = await client.removePhoto(photoId1);
    const missing = await client.removePhoto(photoId1);
    const failed = await client.removePhoto(photoId1);
    const offline = await offlineClient.removePhoto(photoId1);

    expect(removed).toEqual({ kind: "removed" });
    expect(missing).toEqual({ kind: "photoMissing" });
    expect(failed).toMatchObject({ kind: "removeFailed" });
    expect(offline).toEqual({ kind: "removeFailed", reason });
    const paths = requests.map((r) => `${r.method} ${new URL(r.url).pathname}`);
    expect(paths).toEqual(["DELETE /photos/ph1", "DELETE /photos/ph1", "DELETE /photos/ph1"]);
  });

  it("should derive photo content URLs for all supported variants", () => {
    expect(photoContentUrl(photoId1, "thumbnail")).toBe("/photos/ph1/content?variant=thumbnail");
    expect(photoContentUrl(photoId1, "original")).toBe("/photos/ph1/content?variant=original");
  });
});
