import { describe, expect, it } from "vitest";
import { assertSameImage, listRegistryTags, registryImageDigest } from "../src/registryClient";

const credential = () => new Response(JSON.stringify({ token: "temporary-test-token" }));
const tagPage = (tags: unknown, link?: string) =>
  new Response(JSON.stringify({ tags }), { headers: link ? { link } : {} });

describe("listRegistryTags", () => {
  it("should treat a missing first package as an empty tag collection", async () => {
    const fetcher = responsesFetch(credential(), new Response("", { status: 404 }));

    const actualTags = await listRegistryTags(
      fetcher,
      "cosminci/plant-journal",
      "cosminci",
      "test-token",
    );

    expect(actualTags).toEqual([]);
  });

  describe("registryImageDigest", () => {
    it("should identify the immutable digest of a published version", async () => {
      const digest = `sha256:${"a".repeat(64)}`;
      const manifest = new Response(null, { headers: { "Docker-Content-Digest": digest } });
      const fetcher = responsesFetch(credential(), manifest);

      const actualDigest = await registryImageDigest(
        fetcher,
        "owner/image",
        "owner",
        "test-token",
        "1.0.0",
      );

      expect(actualDigest).toBe(digest);
    });

    describe("assertSameImage", () => {
      it("should reject an alias that points to a different image", () => {
        const digest = `sha256:${"a".repeat(64)}`;
        const otherDigest = `sha256:${"b".repeat(64)}`;

        expect(() => {
          assertSameImage(digest, digest);
        }).not.toThrow();
        expect(() => {
          assertSameImage(digest, otherDigest);
        }).toThrow("different image");
      });
    });

    it("should fail visibly if the registry cannot provide a version digest", async () => {
      const missing = responsesFetch(credential(), new Response(null));
      const denied = responsesFetch(credential(), new Response(null, { status: 403 }));

      await expect(
        registryImageDigest(missing, "owner/image", "owner", "test-token", "1.0.0"),
      ).rejects.toThrow("digest");
      await expect(
        registryImageDigest(denied, "owner/image", "owner", "test-token", "1.0.0"),
      ).rejects.toThrow("manifest");
    });
  });

  it("should collect every page before evaluating an existing version", async () => {
    const next = '</v2/cosminci/plant-journal/tags/list?last=1.0.0>; rel="next"';
    const fetcher = responsesFetch(
      credential(),
      tagPage(["1.0.0"], next),
      tagPage(["1.1.0", "latest"]),
    );

    const actualTags = await listRegistryTags(
      fetcher,
      "cosminci/plant-journal",
      "cosminci",
      "test-token",
    );

    expect(actualTags).toEqual(["1.0.0", "1.1.0", "latest"]);
  });

  it("should reject invalid credentials and denied registry reads", async () => {
    const invalidCredentials = responsesFetch(new Response("", { status: 401 }));
    const deniedListing = responsesFetch(credential(), new Response("", { status: 403 }));

    await expect(
      listRegistryTags(invalidCredentials, "owner/image", "owner", "test-token"),
    ).rejects.toThrow("authentication");
    await expect(
      listRegistryTags(deniedListing, "owner/image", "owner", "test-token"),
    ).rejects.toThrow("tag listing");
  });

  it("should refuse malformed credentials and tag collections", async () => {
    const missingToken = responsesFetch(new Response("{}"));
    const invalidTags = responsesFetch(credential(), tagPage(["1.0.0", null]));

    await expect(
      listRegistryTags(missingToken, "owner/image", "owner", "test-token"),
    ).rejects.toThrow("no registry token");
    await expect(
      listRegistryTags(invalidTags, "owner/image", "owner", "test-token"),
    ).rejects.toThrow("invalid tag list");
  });

  it("should refuse an external or missing pagination page", async () => {
    const external = responsesFetch(
      credential(),
      tagPage([], '<https://evil.example/tags>; rel="next"'),
    );
    const missing = responsesFetch(
      credential(),
      tagPage([], '</v2/owner/image/tags/list?next=1>; rel="next"'),
      new Response("", { status: 404 }),
    );

    await expect(listRegistryTags(external, "owner/image", "owner", "test-token")).rejects.toThrow(
      "external pagination",
    );
    await expect(listRegistryTags(missing, "owner/image", "owner", "test-token")).rejects.toThrow(
      "tag listing",
    );
  });
});

function responsesFetch(...responses: Response[]): typeof fetch {
  let next = 0;
  return () => {
    const response = responses[next++];
    if (!response) throw new Error("unexpected registry request");
    return Promise.resolve(response);
  };
}
