export type HealthStatus = "healthy" | "degraded" | "unknown";

export interface Health {
  readonly status: HealthStatus;
  readonly version: string;
  readonly database: boolean;
}

/** Human-readable one-line summary of a health report. */
export function summarize(health: Health): string {
  switch (health.status) {
    case "healthy":
      return `Healthy · v${health.version}`;
    case "degraded":
      return `Degraded · v${health.version}`;
    case "unknown":
      return "Status unknown";
  }
}
