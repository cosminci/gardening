import { afterEach, describe, expect, it, vi } from "vitest";
import { httpHealthClient } from "../../src/adapters/httpHealthClient";

function stubFetch(status: number, body: unknown): void {
  vi.stubGlobal(
    "fetch",
    vi.fn(() =>
      Promise.resolve(
        new Response(JSON.stringify(body), {
          status,
          headers: { "content-type": "application/json" },
        }),
      ),
    ),
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("httpHealthClient", () => {
  it("maps a healthy response", async () => {
    stubFetch(200, {
      status: "Healthy",
      version: "1.0.0",
      database: true,
      checkedAt: "2026-01-01T00:00:00Z",
    });
    const health = await httpHealthClient("http://test").fetchHealth();
    expect(health).toEqual({ status: "healthy", version: "1.0.0", database: true });
  });

  it("maps a degraded response", async () => {
    stubFetch(200, {
      status: "Degraded",
      version: "2.0.0",
      database: false,
      checkedAt: "2026-01-01T00:00:00Z",
    });
    const health = await httpHealthClient("http://test").fetchHealth();
    expect(health).toEqual({ status: "degraded", version: "2.0.0", database: false });
  });

  it("maps an unrecognised status label to unknown", async () => {
    stubFetch(200, {
      status: "Weird",
      version: "3.0.0",
      database: true,
      checkedAt: "2026-01-01T00:00:00Z",
    });
    const health = await httpHealthClient("http://test").fetchHealth();
    expect(health.status).toBe("unknown");
  });

  it("returns unknown when the request fails", async () => {
    stubFetch(503, { message: "unavailable" });
    const health = await httpHealthClient("http://test").fetchHealth();
    expect(health).toEqual({ status: "unknown", version: "", database: false });
  });
});
