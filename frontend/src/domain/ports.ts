import type { Health } from "./health";

/** Port: a source of the service's current health. Implemented by adapters, injected at the
 * composition root.
 */
export interface HealthClient {
  fetchHealth(): Promise<Health>;
}
