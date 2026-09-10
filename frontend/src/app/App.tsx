import { type Component, createResource, Show } from "solid-js";
import { type Health, summarize } from "../domain/health";
import type { HealthClient } from "../domain/ports";

/** Walking-skeleton app shell: fetches health through the injected client and shows it. */
export const App: Component<{ readonly client: HealthClient }> = (props) => {
  const [health] = createResource<Health>(() => props.client.fetchHealth());
  return (
    <main>
      <h1>Gardening</h1>
      <Show when={health()} fallback={<p>Checking status…</p>}>
        {(current) => <p data-testid="status">{summarize(current())}</p>}
      </Show>
    </main>
  );
};
