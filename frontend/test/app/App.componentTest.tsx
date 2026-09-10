import { render, screen } from "@solidjs/testing-library";
import { describe, expect, it } from "vitest";
import { App } from "../../src/app/App";
import type { Health } from "../../src/domain/health";
import type { HealthClient } from "../../src/domain/ports";

function clientReturning(health: Health): HealthClient {
  return { fetchHealth: () => Promise.resolve(health) };
}

describe("App", () => {
  it("renders the loading state, then the health summary", async () => {
    render(() => (
      <App client={clientReturning({ status: "healthy", version: "1.0.0", database: true })} />
    ));
    expect(screen.getByText("Checking status…")).toBeInTheDocument();
    const status = await screen.findByTestId("status");
    expect(status).toHaveTextContent("Healthy · v1.0.0");
  });
});
