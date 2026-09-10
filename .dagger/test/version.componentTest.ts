import { describe, expect, it } from "vitest";
import { deriveVersion } from "../src/version";

describe("deriveVersion", () => {
  it("strips the leading v from an exact tag", () => {
    expect(deriveVersion("v0.1.0")).toBe("0.1.0");
  });
  it("keeps the describe form for an untagged commit", () => {
    expect(deriveVersion("v0.1.0-3-gabc1234")).toBe("0.1.0-3-gabc1234");
  });
  it("passes through a bare sha and falls back when empty", () => {
    expect(deriveVersion("abc1234")).toBe("abc1234");
    expect(deriveVersion("  ")).toBe("0.0.0-unknown");
  });
});
