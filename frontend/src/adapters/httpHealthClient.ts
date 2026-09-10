import createClient from "openapi-fetch";
import type { paths } from "@contract";
import type { Health, HealthStatus } from "../domain/health";
import type { HealthClient } from "../domain/ports";

const statusByLabel: Readonly<Record<string, HealthStatus>> = {
  Healthy: "healthy",
  Degraded: "degraded",
};

function toStatus(label: string): HealthStatus {
  return statusByLabel[label] ?? "unknown";
}

/** HTTP adapter for the health port, using the generated, contract-typed client. */
export function httpHealthClient(baseUrl: string): HealthClient {
  const client = createClient<paths>({ baseUrl });
  return {
    async fetchHealth(): Promise<Health> {
      const { data } = await client.GET("/health");
      if (data === undefined) {
        return { status: "unknown", version: "", database: false };
      }
      return { status: toStatus(data.status), version: data.version, database: data.database };
    },
  };
}
