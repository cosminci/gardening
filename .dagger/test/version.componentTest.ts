import { describe, expect, it } from "vitest";
import { assertReleaseVersion, deriveVersion, releaseVersion } from "../src/version";

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

describe("releaseVersion", () => {
  it("should use the UTC date and time in the reference format", () => {
    const publishedAt = new Date("2026-09-24T07:06:09.000Z");

    expect(releaseVersion(publishedAt)).toBe("2026.9.24-T070609");
  });

  describe("assertReleaseVersion", () => {
    it("should accept a UTC timestamp without altering its image tag", () => {
      expect(() => {
        assertReleaseVersion("2026.9.24-T170609");
      }).not.toThrow();
    });

    it("should reject old semantic versions and malformed timestamps", () => {
      for (const tag of ["v1.0.0", "1.0.0-rc.2", "2026.13.24-T170609", "2026.9.24-T1706"]) {
        expect(() => {
          assertReleaseVersion(tag);
        }).toThrow("invalid release version");
      }
    });
  });

  it("should distinguish releases in different seconds, including across midnight", () => {
    const before = new Date("2026-09-24T23:59:59.000Z");
    const after = new Date("2026-09-25T00:00:00.000Z");

    expect(releaseVersion(before)).toBe("2026.9.24-T235959");
    expect(releaseVersion(after)).toBe("2026.9.25-T000000");
  });
});
