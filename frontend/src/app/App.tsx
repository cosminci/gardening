import { For, Match, Show, Switch, createSignal, onMount } from "solid-js";
import type { Component } from "solid-js";
import type * as Journal from "../domain/Journal";
import { JournalHeader } from "./JournalHeader";
import { displayJournalUpdate } from "./JournalTransition";
import { OperationSheet, operationControlId, type OperationTarget } from "./OperationSheet";
import { PlantCard } from "./PlantCard";
import "./app.css";

interface AppProps {
  readonly journal: Journal.JournalClient;
}

interface PlantHistory {
  readonly plant: Journal.Plant;
  readonly operations: readonly Journal.Operation[];
}

type ViewState = "loading" | "failed" | "loaded";

export const App: Component<AppProps> = (props) => {
  const [view, setView] = createSignal<ViewState>("loading");
  const [histories, setHistories] = createSignal<readonly PlantHistory[]>([]);
  const [substrateComponents, setSubstrateComponents] = createSignal<
    readonly Journal.SubstrateComponent[]
  >([]);
  const [pesticides, setPesticides] = createSignal<readonly Journal.Pesticide[]>([]);
  const [formTarget, setFormTarget] = createSignal<OperationTarget>();
  const [saveError, setSaveError] = createSignal<string>();

  const loadJournal = async (animate = false) => {
    const journal = props.journal;
    const [plantsResult, componentsResult, pesticidesResult] = await Promise.all([
      journal.getPlants(),
      journal.getSubstrateComponents(),
      journal.getPesticides(),
    ]);
    if (
      plantsResult.kind !== "read" ||
      componentsResult.kind !== "read" ||
      pesticidesResult.kind !== "read"
    ) {
      setView("failed");
      return;
    }
    setSubstrateComponents(componentsResult.entries);
    setPesticides(pesticidesResult.entries);

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
    loaded.sort((first, second) => name(first.plant).localeCompare(name(second.plant)));
    const display = () => {
      setHistories(loaded);
      setView("loaded");
    };
    await displayJournalUpdate(animate && formTarget() === undefined, display);
  };

  const saveOperation = async (target: OperationTarget, details: Journal.OperationDetails) => {
    const setTargetError = (message: string) => formTarget() === target && setSaveError(message);
    try {
      if (target.kind === "log") {
        const result = await props.journal.logOperation(target.plantId, details);
        if (result.kind !== "logged") {
          setTargetError("The operation could not be saved.");
          return;
        }
      } else {
        const result = await props.journal.editOperation(target.operation.id, details);
        if (result.kind !== "edited") {
          setTargetError(
            result.kind === "operationMissing"
              ? "This operation no longer exists."
              : result.kind === "operationTypeMismatch"
                ? "The operation type cannot be changed."
                : "The operation could not be saved.",
          );
          return;
        }
      }
    } catch {
      setTargetError("The operation could not be saved.");
      return;
    }
    if (formTarget() === target) {
      setFormTarget(undefined);
      setSaveError(undefined);
    }
    await loadJournal(target.kind === "log").catch(() => {
      setView("failed");
    });
    if (formTarget() === undefined) document.getElementById(operationControlId(target))?.focus();
  };

  const addSubstrateComponent = async (data: Journal.SubstrateComponentData) => {
    const result = await props.journal.addSubstrateComponent(data);
    if (result.kind === "added") setSubstrateComponents((current) => [...current, result.entry]);
    return result;
  };

  const editSubstrateComponent: Journal.JournalClient["editSubstrateComponent"] = async (
    id,
    data,
  ) => {
    const result = await props.journal.editSubstrateComponent(id, data);
    if (result.kind === "edited")
      setSubstrateComponents((current) =>
        current.map((component) => (component.id === id ? result.entry : component)),
      );
    return result;
  };

  const addPesticide = async (data: Journal.PesticideData) => {
    const result = await props.journal.addPesticide(data);
    if (result.kind === "added") setPesticides((current) => [...current, result.entry]);
    return result;
  };

  const editPesticide: Journal.JournalClient["editPesticide"] = async (id, data) => {
    const result = await props.journal.editPesticide(id, data);
    if (result.kind === "edited")
      setPesticides((current) =>
        current.map((pesticide) => (pesticide.id === id ? result.entry : pesticide)),
      );
    return result;
  };

  onMount(() => {
    void loadJournal().catch(() => {
      setView("failed");
    });
  });

  return (
    <main>
      <JournalHeader loaded={view() === "loaded"} plantCount={histories().length} />
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
              {(history) => (
                <PlantCard
                  plant={history.plant}
                  operations={history.operations}
                  substrateComponents={substrateComponents()}
                  pesticides={pesticides()}
                  onLog={() => {
                    setSaveError(undefined);
                    setFormTarget({ kind: "log", plantId: history.plant.id });
                  }}
                  onEdit={(operation) => {
                    setSaveError(undefined);
                    setFormTarget({ kind: "edit", operation });
                  }}
                />
              )}
            </For>
          </section>
        </Match>
      </Switch>
      <Show when={formTarget()} keyed>
        {(target) => (
          <OperationSheet
            target={target}
            substrateComponents={substrateComponents()}
            pesticides={pesticides()}
            saveError={saveError()}
            onSubmit={(details) => saveOperation(target, details)}
            onAddSubstrateComponent={addSubstrateComponent}
            onEditSubstrateComponent={editSubstrateComponent}
            onAddPesticide={addPesticide}
            onEditPesticide={editPesticide}
            onCancel={() => {
              setFormTarget(undefined);
            }}
          />
        )}
      </Show>
    </main>
  );
};

const name = (plant: Journal.Plant) => plant.details.maybeNickname ?? plant.details.species;
