import {
  Index,
  Match,
  Show,
  Switch,
  createEffect,
  createSignal,
  onCleanup,
  onMount,
} from "solid-js";
import type { Component } from "solid-js";
import type * as Journal from "../domain/Journal";
import type { OperationClient } from "../domain/Operation";
import type { PesticideClient } from "../domain/PesticideCatalog";
import type { PlantClient } from "../domain/Plant";
import type { PlantAttentionClient } from "../domain/PlantAttention";
import type { SubstrateComponentClient } from "../domain/SubstrateComponentCatalog";
import { ArchiveConfirmation } from "./ArchiveConfirmation";
import { JournalHeader } from "./JournalHeader";
import { displayJournalUpdate } from "./JournalTransition";
import { recentOperationCount, type OperationHistoryChange } from "./OperationHistory";
import { OperationSheet, operationControlId, type OperationTarget } from "./OperationSheet";
import { orderPlantAttention } from "./PlantAttentionOrdering";
import { PlantCard } from "./PlantCard";
import { PlantSheet } from "./PlantSheet";
import "./app.css";

interface AppProps {
  readonly plants: PlantClient;
  readonly operations: OperationClient;
  readonly attention: PlantAttentionClient;
  readonly substrates: SubstrateComponentClient;
  readonly pesticideCatalog: PesticideClient;
}

interface GardenHistory {
  readonly kind: "garden";
  readonly attention: Journal.PlantAttention;
  readonly measuredAt: Journal.Instant;
  readonly page: Journal.OperationPage;
}

interface CemeteryHistory {
  readonly kind: "cemetery";
  readonly plant: Journal.Plant;
  readonly dates: Journal.OperationDates;
  readonly page: Journal.OperationPage;
}

type ViewState = "loading" | "failed" | "loaded";
type PlantView = "garden" | "cemetery";

const viewFromUrl = (): PlantView =>
  new URLSearchParams(window.location.search).get("view") === "cemetery" ? "cemetery" : "garden";

export const App: Component<AppProps> = (props) => {
  const [view, setView] = createSignal<ViewState>("loading");
  const [gardenHistories, setGardenHistories] = createSignal<readonly GardenHistory[]>([]);
  const [cemeteryHistories, setCemeteryHistories] = createSignal<readonly CemeteryHistory[]>([]);
  const [cemeteryCount, setCemeteryCount] = createSignal(0);
  const [selected, setSelected] = createSignal<PlantView>(viewFromUrl());
  const [ready, setReady] = createSignal(false);
  const [archiveTarget, setArchiveTarget] = createSignal<Journal.Plant>();
  const [archiveCompleted, setArchiveCompleted] = createSignal(false);
  const [substrateComponents, setSubstrateComponents] = createSignal<
    readonly Journal.SubstrateComponent[]
  >([]);
  const [pesticides, setPesticides] = createSignal<readonly Journal.Pesticide[]>([]);
  const [formTarget, setFormTarget] = createSignal<OperationTarget>();
  const [saveError, setSaveError] = createSignal<string>();
  const [plantSheetOpen, setPlantSheetOpen] = createSignal(false);
  const [plantSheetCompleted, setPlantSheetCompleted] = createSignal(false);
  const [plantSaveError, setPlantSaveError] = createSignal<string>();
  const [creationReloadFailed, setCreationReloadFailed] = createSignal(false);
  const [operationChange, setOperationChange] = createSignal<OperationHistoryChange>();
  const recentlyArchived = new Set<Journal.PlantId>();
  let loadVersion = 0;
  let latestAttention: Journal.AttentionProjection | undefined;
  let initialViewLoaded = false;
  const histories = () => (selected() === "garden" ? gardenHistories() : cemeteryHistories());

  const loadJournal = async (animate = false, cachedAttention?: Journal.AttentionProjection) => {
    const version = ++loadVersion;
    const plants = props.plants;
    const operations = props.operations;
    const [plantsResult, countResult, attentionResult, componentsResult, pesticidesResult] =
      await Promise.all([
        plants.getPlants(),
        plants.getArchivedCount(),
        cachedAttention === undefined
          ? props.attention.getAttention()
          : Promise.resolve({ kind: "read" as const, projection: cachedAttention }),
        props.substrates.getSubstrateComponents(),
        props.pesticideCatalog.getPesticides(),
      ]);
    if (version !== loadVersion) return;
    if (
      plantsResult.kind !== "read" ||
      countResult.kind !== "read" ||
      attentionResult.kind !== "read" ||
      componentsResult.kind !== "read" ||
      pesticidesResult.kind !== "read"
    ) {
      setView("failed");
      return;
    }
    setSubstrateComponents(componentsResult.entries);
    setPesticides(pesticidesResult.entries);
    latestAttention = attentionResult.projection;

    const attentionSamples = attentionResult.projection.plants;
    for (const id of recentlyArchived)
      if (!attentionSamples.some((sample) => sample.plantId === id)) recentlyArchived.delete(id);
    const attentionById = new Map(
      attentionSamples
        .filter((sample) => !recentlyArchived.has(sample.plantId))
        .map((sample) => [sample.plantId, sample]),
    );
    const activeIds = new Set(plantsResult.plants.map((plant) => plant.id));
    if (
      attentionSamples.length !== new Set(attentionSamples.map((sample) => sample.plantId)).size ||
      [...attentionById.keys()].some((id) => !activeIds.has(id))
    ) {
      setView("failed");
      return;
    }
    const results = await Promise.all(
      plantsResult.plants.map(async (plant) => ({
        plant,
        sample: attentionById.get(plant.id),
        operationsResult: await operations.getOperations(plant.id, {
          offset: 0,
          size: recentOperationCount,
        }),
      })),
    );
    if (version !== loadVersion) return;
    const loaded: GardenHistory[] = [];
    for (const { plant, sample, operationsResult } of results) {
      if (operationsResult.kind !== "read") {
        setView("failed");
        return;
      }
      let watering = sample?.watering;
      if (watering === undefined) {
        const count = await countWaterings(plant.id, operationsResult.page, operations);
        if (version !== loadVersion) return;
        if (count === undefined || count >= 5) {
          setView("failed");
          return;
        }
        watering = { kind: "unavailable", sampleCount: count, maybeElapsed: null };
      }
      loaded.push({
        kind: "garden",
        attention: { plant, watering },
        measuredAt: attentionResult.projection.measuredAt,
        page: operationsResult.page,
      });
    }
    const ordered = orderPlantAttention(loaded.map((history) => history.attention));
    const orderedHistories = loaded.toSorted(
      (first, second) => ordered.indexOf(first.attention) - ordered.indexOf(second.attention),
    );
    const display = () => {
      if (version !== loadVersion) return;
      setGardenHistories(orderedHistories);
      setCemeteryCount(countResult.count);
      setReady(true);
      setView("loaded");
    };
    await displayJournalUpdate(animate && formTarget() === undefined, display);
  };

  const loadCemetery = async (preserveView = false) => {
    const version = ++loadVersion;
    const plants = props.plants;
    const operations = props.operations;
    if (!preserveView) setView("loading");
    const result = await plants.getPlants("archived");
    if (version !== loadVersion) return;
    if (result.kind !== "read") {
      setView("failed");
      return;
    }
    const records = await Promise.all(
      result.plants.map(async (plant) => ({
        plant,
        datesResult: await operations.getOperationDates(plant.id),
        operationsResult: await operations.getOperations(plant.id, {
          offset: 0,
          size: recentOperationCount,
        }),
      })),
    );
    if (version !== loadVersion) return;
    const loaded: CemeteryHistory[] = [];
    for (const { plant, datesResult, operationsResult } of records)
      if (datesResult.kind === "read" && operationsResult.kind === "read")
        loaded.push({
          kind: "cemetery",
          plant,
          dates: datesResult.dates,
          page: operationsResult.page,
        });
      else {
        setView("failed");
        return;
      }
    setCemeteryHistories(loaded);
    setCemeteryCount(result.plants.length);
    setView("loaded");
  };

  const selectView = (next: PlantView, updateUrl = true) => {
    if (updateUrl) {
      const url = new URL(window.location.href);
      if (next === "cemetery") url.searchParams.set("view", "cemetery");
      else url.searchParams.delete("view");
      if (url.href !== window.location.href)
        window.history.pushState(window.history.state, "", url);
    }
    if (next === "garden") {
      ++loadVersion;
      setSelected("garden");
      setView("loaded");
    } else if (selected() !== "cemetery" || view() !== "loaded") {
      setSelected("cemetery");
      const pending = loadCemetery();
      const version = loadVersion;
      void pending.catch(() => {
        if (version === loadVersion) setView("failed");
      });
    }
  };

  const confirmArchive = async (plant: Journal.Plant) => {
    let result: Journal.ArchivePlantResult;
    try {
      result = await props.plants.archivePlant(plant.id);
    } catch {
      return "The plant could not be archived.";
    }
    if (result.kind === "plantMissing") return "This plant no longer exists.";
    if (result.kind === "alreadyArchived") return "This plant was already archived.";
    if (result.kind === "archiveFailed") return "The plant could not be archived.";
    recentlyArchived.add(plant.id);
    await loadJournal().catch(() => setView("failed"));
    setArchiveCompleted(true);
    setArchiveTarget(undefined);
    return undefined;
  };

  const savePlant = async (details: Journal.NewPlantDetails) => {
    setPlantSaveError(undefined);
    let result: Journal.CreatePlantResult;
    try {
      result = await props.plants.createPlant(details);
    } catch {
      setPlantSaveError("The plant could not be saved.");
      return;
    }
    if (result.kind !== "created") {
      setPlantSaveError(
        result.kind === "unknownComponent"
          ? "Choose known substrate components."
          : result.kind === "catalogReadFailed"
            ? "The substrate catalog could not be read. Try again."
            : "The plant could not be saved.",
      );
      return;
    }
    setPlantSheetCompleted(true);
    setPlantSheetOpen(false);
    selectView("garden");
    setCreationReloadFailed(false);
    await loadJournal(false, latestAttention).catch(() => setView("failed"));
    if (view() === "failed") setCreationReloadFailed(true);
    document.getElementById("garden-toggle")?.focus();
  };

  const saveOperation = async (
    target: OperationTarget,
    details: Journal.OperationDetails,
    date: Journal.Instant,
  ) => {
    const setTargetError = (message: string) => formTarget() === target && setSaveError(message);
    let editedOperation: Journal.Operation | undefined;
    try {
      if (target.kind === "log") {
        const result = await props.operations.logOperation(target.plantId, date, details);
        if (result.kind !== "logged") {
          setTargetError(
            result.kind === "plantArchived"
              ? "This plant is archived; new operations cannot be added."
              : "The operation could not be saved.",
          );
          return;
        }
      } else {
        const result = await props.operations.editOperation(target.operation.id, details);
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
        editedOperation = result.operation;
      }
    } catch {
      setTargetError("The operation could not be saved.");
      return;
    }
    if (formTarget() === target) {
      setFormTarget(undefined);
      setSaveError(undefined);
    }
    const refresh =
      selected() === "garden" ? loadJournal(target.kind === "log") : loadCemetery(true);
    const version = loadVersion;
    await refresh.catch(() => {
      if (version === loadVersion) setView("failed");
    });
    setOperationChange(
      editedOperation === undefined
        ? { kind: "logged" }
        : { kind: "edited", operation: editedOperation },
    );
    if (formTarget() === undefined) document.getElementById(operationControlId(target))?.focus();
  };

  const addSubstrateComponent = async (data: Journal.SubstrateComponentData) => {
    const result = await props.substrates.addSubstrateComponent(data);
    if (result.kind === "added") setSubstrateComponents((current) => [...current, result.entry]);
    return result;
  };

  const editSubstrateComponent: SubstrateComponentClient["editSubstrateComponent"] = async (
    id,
    data,
  ) => {
    const result = await props.substrates.editSubstrateComponent(id, data);
    if (result.kind === "edited")
      setSubstrateComponents((current) =>
        current.map((component) => (component.id === id ? result.entry : component)),
      );
    return result;
  };

  const addPesticide = async (data: Journal.PesticideData) => {
    const result = await props.pesticideCatalog.addPesticide(data);
    if (result.kind === "added") setPesticides((current) => [...current, result.entry]);
    return result;
  };

  const editPesticide: PesticideClient["editPesticide"] = async (id, data) => {
    const result = await props.pesticideCatalog.editPesticide(id, data);
    if (result.kind === "edited")
      setPesticides((current) =>
        current.map((pesticide) => (pesticide.id === id ? result.entry : pesticide)),
      );
    return result;
  };

  createEffect(() => {
    if (!ready() || initialViewLoaded) return;
    initialViewLoaded = true;
    if (selected() === "cemetery") {
      const pending = loadCemetery();
      const version = loadVersion;
      void pending.catch(() => {
        if (version === loadVersion) setView("failed");
      });
    }
  });

  onMount(() => {
    const navigate = () => {
      selectView(viewFromUrl(), false);
    };
    window.addEventListener("popstate", navigate);
    onCleanup(() => {
      window.removeEventListener("popstate", navigate);
    });
    void loadJournal().catch(() => {
      setView("failed");
    });
  });

  return (
    <main>
      <JournalHeader
        loaded={ready()}
        gardenCount={gardenHistories().length}
        cemeteryCount={cemeteryCount()}
        selected={selected()}
        onSelect={selectView}
        onAddPlant={() => {
          setPlantSaveError(undefined);
          setPlantSheetCompleted(false);
          setPlantSheetOpen(true);
        }}
      />
      <Switch>
        <Match when={view() === "loading"}>
          <p class="page-state">Loading your journal…</p>
        </Match>
        <Match when={view() === "failed"}>
          <p class="page-state page-state--error" role="alert">
            {creationReloadFailed()
              ? "Plant was saved, but the garden could not be loaded."
              : "The journal could not be loaded."}
          </p>
        </Match>
        <Match when={view() === "loaded"}>
          <section class="journal" aria-label={selected() === "garden" ? "Garden" : "Cemetery"}>
            <Index each={histories()}>
              {(history) => {
                const garden = () => {
                  const entry = history();
                  return entry.kind === "garden" ? entry : undefined;
                };
                const cemetery = () => {
                  const entry = history();
                  return entry.kind === "cemetery" ? entry : undefined;
                };
                return (
                  <>
                    <Show when={garden()}>
                      {(entry) => (
                        <PlantCard
                          attention={entry().attention}
                          measuredAt={entry().measuredAt}
                          operationPage={entry().page}
                          substrateComponents={substrateComponents()}
                          pesticides={pesticides()}
                          getOperations={(window) =>
                            props.operations.getOperations(entry().attention.plant.id, window)
                          }
                          operationChange={operationChange()}
                          onLog={() => {
                            setSaveError(undefined);
                            setFormTarget({ kind: "log", plantId: entry().attention.plant.id });
                          }}
                          onArchive={() => {
                            setArchiveCompleted(false);
                            setArchiveTarget(entry().attention.plant);
                          }}
                          onEdit={(operation) => {
                            setSaveError(undefined);
                            setFormTarget({ kind: "edit", operation });
                          }}
                        />
                      )}
                    </Show>
                    <Show when={cemetery()}>
                      {(entry) => (
                        <PlantCard
                          kind="cemetery"
                          plant={entry().plant}
                          dates={entry().dates}
                          operationPage={entry().page}
                          substrateComponents={substrateComponents()}
                          pesticides={pesticides()}
                          getOperations={(window) =>
                            props.operations.getOperations(entry().plant.id, window)
                          }
                          operationChange={operationChange()}
                          onEdit={(operation) => {
                            setSaveError(undefined);
                            setFormTarget({ kind: "edit", operation });
                          }}
                        />
                      )}
                    </Show>
                  </>
                );
              }}
            </Index>
          </section>
        </Match>
      </Switch>
      <Show when={plantSheetOpen()}>
        <PlantSheet
          components={substrateComponents()}
          saveError={plantSaveError()}
          completed={plantSheetCompleted()}
          onSubmit={savePlant}
          onAddComponent={addSubstrateComponent}
          onEditComponent={editSubstrateComponent}
          onCancel={() => setPlantSheetOpen(false)}
        />
      </Show>
      <Show when={formTarget()} keyed>
        {(target) => (
          <OperationSheet
            target={target}
            substrateComponents={substrateComponents()}
            pesticides={pesticides()}
            saveError={saveError()}
            onSubmit={(details, date) => saveOperation(target, details, date)}
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
      <Show when={archiveTarget()} keyed>
        {(plant) => (
          <ArchiveConfirmation
            plant={plant}
            completed={archiveCompleted()}
            onConfirm={() => confirmArchive(plant)}
            onCancel={() => {
              setArchiveTarget(undefined);
            }}
          />
        )}
      </Show>
    </main>
  );
};

const countWaterings = async (
  plantId: Journal.PlantId,
  firstPage: Journal.OperationPage,
  operations: OperationClient,
): Promise<number | undefined> => {
  let page = firstPage;
  let count = page.operations.filter(
    (operation) => operation.details.kind === "care" && operation.details.actions.has("watered"),
  ).length;
  let offset = 0;
  while (count < 5 && page.hasNextPage) {
    if (page.operations.length === 0) return undefined;
    offset += page.operations.length;
    const next = await operations.getOperations(plantId, { offset, size: 10 });
    if (next.kind !== "read") return undefined;
    page = next.page;
    count += page.operations.filter(
      (operation) => operation.details.kind === "care" && operation.details.actions.has("watered"),
    ).length;
  }
  return count;
};
