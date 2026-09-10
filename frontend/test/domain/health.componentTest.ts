import { describe, expect, it } from "vitest";
import { summarize } from "../../src/domain/health";

describe("summarize", () => {
  it("labels a healthy report", () => {
    expect(summarize({ status: "healthy", version: "1.0.0", database: true })).toBe(
      "Healthy · v1.0.0",
    );
  });

  it("labels a degraded report", () => {
    expect(summarize({ status: "degraded", version: "2.0.0", database: false })).toBe(
      "Degraded · v2.0.0",
    );
  });

  it("labels an unknown report", () => {
    expect(summarize({ status: "unknown", version: "", database: false })).toBe("Status unknown");
  });
});
