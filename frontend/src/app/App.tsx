import { For, Match, Show, Switch, createSignal, onMount } from "solid-js";
import type { Component } from "solid-js";
import type { JournalClient, Operation, Plant } from "../domain/Journal";
import { PlantCard } from "./PlantCard";
import "./app.css";

interface AppProps {
  readonly journal: JournalClient;
}

interface PlantHistory {
  readonly plant: Plant;
  readonly operations: readonly Operation[];
}

type ViewState = "loading" | "failed" | "loaded";

export const App: Component<AppProps> = (props) => {
  const [view, setView] = createSignal<ViewState>("loading");
  const [histories, setHistories] = createSignal<readonly PlantHistory[]>([]);

  const loadJournal = async () => {
    const journal = props.journal;
    const plantsResult = await journal.getPlants();
    if (plantsResult.kind !== "read") {
      setView("failed");
      return;
    }

    const results = await Promise.all(
      plantsResult.plants.map(async (plant) => ({
        plant,
        operationsResult: await journal.getOperations(plant.id),
      })),
    );
    const loaded: PlantHistory[] = [];
    for (const { plant, operationsResult } of results)
      if (operationsResult.kind === "read")
        loaded.push({ plant, operations: operationsResult.operations });
      else {
        setView("failed");
        return;
      }
    setHistories(loaded);
    setView("loaded");
  };

  onMount(() => void loadJournal());

  return (
    <main>
      <header class="masthead">
        <div class="brand-mark" aria-hidden="true">
          G
        </div>
        <div>
          <p class="eyebrow">Home greenhouse</p>
          <h1>Plant journal</h1>
          <p class="masthead__summary">Care history, growing conditions, and repotting notes.</p>
        </div>
        <Show when={view() === "loaded"}>
          <p class="plant-count">
            <strong>{histories().length}</strong>
            <span>active {histories().length === 1 ? "plant" : "plants"}</span>
          </p>
        </Show>
      </header>
      <Switch>
        <Match when={view() === "loading"}>
          <p class="page-state">Loading your journal…</p>
        </Match>
        <Match when={view() === "failed"}>
          <p class="page-state page-state--error" role="alert">
            The journal could not be loaded.
          </p>
        </Match>
        <Match when={view() === "loaded"}>
          <section class="journal" aria-label="Plant journal">
            <For each={histories()}>
              {(history) => <PlantCard plant={history.plant} operations={history.operations} />}
            </For>
          </section>
        </Match>
      </Switch>
    </main>
  );
};
