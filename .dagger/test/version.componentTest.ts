import { describe, expect, it } from "vitest";
import {
  assertReleaseSource,
  compareStableVersions,
  deriveVersion,
  planLatestRepair,
  planPublication,
} from "../src/version";

describe("deriveVersion", () => {
  it("should strip the leading v from an exact tag", () => {
    expect(deriveVersion("v0.1.0")).toBe("0.1.0");
  });
  it("should keep the describe form for an untagged commit", () => {
    expect(deriveVersion("v0.1.0-3-gabc1234")).toBe("0.1.0-3-gabc1234");
  });
  it("should pass through a bare sha and fall back when empty", () => {
    expect(deriveVersion("abc1234")).toBe("abc1234");
    expect(deriveVersion("  ")).toBe("0.0.0-unknown");
  });
});

describe("planPublication", () => {
  it("should publish the first stable release as the latest image", () => {
    const expectedPlan = { version: "1.0.0", updateLatest: true };

    expect(planPublication("v1.0.0", [])).toEqual(expectedPlan);
  });

  it("should keep prereleases out of the stable update channel", () => {
    const expectedPlan = { version: "1.0.0-rc.1", updateLatest: false };

    expect(planPublication("v1.0.0-rc.1", [])).toEqual(expectedPlan);
  });

  it("should leave latest at the highest stable SemVer across maintenance releases", () => {
    const existingTags = ["1.9.0", "1.10.0", "latest", "2.0.0-rc.1"];
    const expectedPlan = { version: "1.9.1", updateLatest: false };

    expect(planPublication("v1.9.1", existingTags)).toEqual(expectedPlan);
    expect(planPublication("v1.11.0", existingTags).updateLatest).toBe(true);
  });

  it("should compare all version components without losing precision", () => {
    expect(compareStableVersions("9007199254740993.0.0", "9007199254740992.99.99")).toBe(1);
    expect(compareStableVersions("1.0.0", "1.0.0")).toBe(0);
  });

  it("should reject an already published version without overwriting it", () => {
    expect(() => planPublication("v1.0.0", ["1.0.0", "latest"])).toThrow("already published");
  });

  it("should reject noncanonical stable and release-candidate tags", () => {
    const invalidTags = [
      "1.0.0",
      "v01.0.0",
      "v1.0.0-rc.0",
      "v1.0.0-beta.1",
      "v1.0",
      "v1.0.0-dirty",
    ];

    for (const tag of invalidTags) {
      expect(() => planPublication(tag, [])).toThrow("invalid release tag");
    }
  });
});

describe("assertReleaseSource", () => {
  const candidate = {
    tag: "v1.0.0",
    clean: true,
    tagType: "tag",
    headRevision: "a1b2",
    tagRevision: "a1b2",
    mainAncestor: "a1b2",
  };

  it("should accept a stable release merged to main and a prerelease still in review", () => {
    expect(() => {
      assertReleaseSource(candidate);
    }).not.toThrow();
    expect(() => {
      assertReleaseSource({ ...candidate, tag: "v1.0.0-rc.1", mainAncestor: "base" });
    }).not.toThrow();
  });

  it("should reject dirty, lightweight, and mismatched release refs", () => {
    expect(() => {
      assertReleaseSource({ ...candidate, clean: false });
    }).toThrow("dirty");
    expect(() => {
      assertReleaseSource({ ...candidate, tagType: "commit" });
    }).toThrow("annotated");
    expect(() => {
      assertReleaseSource({ ...candidate, tagRevision: "base" });
    }).toThrow("HEAD");
  });

  it("should reject stable releases outside merged main", () => {
    expect(() => {
      assertReleaseSource({ ...candidate, mainAncestor: "base" });
    }).toThrow("main");
  });
});

describe("planLatestRepair", () => {
  it("should allow finishing the latest alias after a version was published", () => {
    expect(planLatestRepair("v1.0.0", ["1.0.0", "latest"])).toBe("1.0.0");
  });

  it("should refuse unpublished, prerelease, and superseded tags", () => {
    expect(() => planLatestRepair("v1.0.0", [])).toThrow("not published");
    expect(() => planLatestRepair("v1.0.0-rc.1", ["1.0.0-rc.1"])).toThrow("stable");
    expect(() => planLatestRepair("v1.0.0", ["1.0.0", "1.1.0"])).toThrow("newer");
  });
});
