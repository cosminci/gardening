import {
  Match,
  Show,
  Switch,
  createEffect,
  createMemo,
  createSignal,
  onCleanup,
  onMount,
} from "solid-js";
import type { Component } from "solid-js";
import type * as Journal from "../domain/Journal";
import type { OperationClient } from "../domain/Operation";
import type { PesticideClient } from "../domain/PesticideCatalog";
import type { PlantClient } from "../domain/Plant";
import type { PlantPhotoClient } from "../domain/PlantPhoto";
import type { FeedConnectionState, PlantAttentionFeed } from "../domain/PlantAttention";
import { isAttentionProjectionValid } from "../domain/PlantAttention";
import type { SubstrateClient } from "../domain/SubstrateCatalog";
import { ArchiveConfirmation } from "./ArchiveConfirmation";
import { PlantPhotosSheet } from "./PlantPhotosSheet";
import { DeleteOperationConfirmation } from "./DeleteOperationConfirmation";
import { DeleteSubstrateMixConfirmation } from "./DeleteSubstrateMixConfirmation";
import { JournalHeader } from "./JournalHeader";
import { JournalList, type CemeteryHistory, type GardenHistory } from "./JournalList";
import { displayJournalUpdate } from "./JournalTransition";
import * as Controls from "./OperationControlIds";
import { recentOperationCount, type OperationHistoryChange } from "./OperationHistory";
import { OperationSheet, operationControlId, type OperationTarget } from "./OperationSheet";
import { orderPlantAttention } from "./PlantAttentionOrdering";
import { filterHistoriesBySearch } from "./PlantSearch";
import { createPesticideCatalogController } from "./PesticideCatalogController";
import { PlantSheet, editPlantControlId, type PlantTarget } from "./PlantSheet";
import { createSubstrateCatalogController } from "./SubstrateCatalogController";
import "./app.css";

interface AppProps {
  readonly plants: PlantClient;
  readonly operations: OperationClient;
  readonly attention: PlantAttentionFeed;
  readonly substrates: SubstrateClient;
  readonly pesticideCatalog: PesticideClient;
  readonly photos: PlantPhotoClient;
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
  const [searchQuery, setSearchQuery] = createSignal("");
  const [ready, setReady] = createSignal(false);
  const [archiveTarget, setArchiveTarget] = createSignal<Journal.Plant>();
  const [archiveCompleted, setArchiveCompleted] = createSignal(false);
  const [photosTarget, setPhotosTarget] = createSignal<Journal.Plant>();
  const [substrateComponents, setSubstrateComponents] = createSignal<
    readonly Journal.SubstrateComponent[]
  >([]);
  const [substrateMixes, setSubstrateMixes] = createSignal<readonly Journal.SubstrateMix[]>([]);
  const [deleteMixTarget, setDeleteMixTarget] = createSignal<Journal.SubstrateMix>();
  const [deleteMixCompleted, setDeleteMixCompleted] = createSignal(false);
  const [pesticides, setPesticides] = createSignal<readonly Journal.Pesticide[]>([]);
  const [formTarget, setFormTarget] = createSignal<OperationTarget>();
  const [saveError, setSaveError] = createSignal<string>();
  const [plantTarget, setPlantTarget] = createSignal<PlantTarget>();
  const [deleteTarget, setDeleteTarget] = createSignal<Journal.Operation>();
  const [deleteCompleted, setDeleteCompleted] = createSignal(false);
  const [plantSheetCompleted, setPlantSheetCompleted] = createSignal(false);
  const [plantSaveError, setPlantSaveError] = createSignal<string>();
  const [creationReloadFailed, setCreationReloadFailed] = createSignal(false);
  const [operationChange, setOperationChange] = createSignal<OperationHistoryChange>();
  const [attentionProjection, setAttentionProjection] = createSignal<Journal.AttentionProjection>();
  const [feedConnectionState, setFeedConnectionState] =
    createSignal<FeedConnectionState>("connecting");
  const [lastAttentionUpdate, setLastAttentionUpdate] = createSignal<number>();
  const recentlyArchived = new Set<Journal.PlantId>();
  let activeIds = new Set<Journal.PlantId>();
  let activePlantIdsLoaded = false;
  let loadVersion = 0;
  let initialViewLoaded = false;
  let unsubscribeFeed: (() => void) | undefined;

  const orderedGardenHistories = createMemo(() => {
    const proj = attentionProjection();
    const histories = gardenHistories();
    if (proj === undefined) return histories;
    const samplesById = new Map(proj.plants.map((s) => [s.plant, s]));
    const pending: GardenHistory[] = [];
    const knownHistories: GardenHistory[] = [];
    const plantAttentions: { plant: Journal.Plant; watering: Journal.WateringAttention }[] = [];
    for (const h of histories) {
      const sample = samplesById.get(h.plant.id);
      if (sample !== undefined) {
        knownHistories.push(h);
        plantAttentions.push({ plant: h.plant, watering: sample.watering });
      } else {
        pending.push(h);
      }
    }
    const ordered = orderPlantAttention(plantAttentions);
    const sortedKnown = knownHistories.toSorted(
      (a, b) =>
        ordered.findIndex((pa) => pa.plant.id === a.plant.id) -
        ordered.findIndex((pa) => pa.plant.id === b.plant.id),
    );
    return [...pending, ...sortedKnown];
  });

  const histories = () =>
    selected() === "garden" ? orderedGardenHistories() : cemeteryHistories();

  const filteredHistories = createMemo(() => filterHistoriesBySearch(histories(), searchQuery()));

  const emptyMessage = createMemo(() =>
    searchQuery().trim() !== "" && filteredHistories().length === 0
      ? "No plants match your search."
      : undefined,
  );

  const loadJournal = async (animate = false) => {
    const version = ++loadVersion;
    const plants = props.plants;
    const operations = props.operations;
    const [plantsResult, countResult, componentsResult, mixesResult, pesticidesResult] =
      await Promise.all([
        plants.getPlants(),
        plants.getArchivedCount(),
        props.substrates.getSubstrateComponents(),
        props.substrates.getSubstrateMixes(),
        props.pesticideCatalog.getPesticides(),
      ]);
    if (version !== loadVersion) return;
    if (
      plantsResult.kind !== "read" ||
      countResult.kind !== "read" ||
      componentsResult.kind !== "read" ||
      mixesResult.kind !== "read" ||
      pesticidesResult.kind !== "read"
    ) {
      setView("failed");
      return;
    }
    setSubstrateComponents(componentsResult.entries);
    setSubstrateMixes(mixesResult.entries);
    setPesticides(pesticidesResult.entries);

    const newActiveIds = new Set(plantsResult.plants.map((plant) => plant.id));
    const proj = attentionProjection();
    if (proj !== undefined && !isAttentionProjectionValid(proj, newActiveIds, recentlyArchived)) {
      setView("failed");
      return;
    }
    activeIds = newActiveIds;
    activePlantIdsLoaded = true;

    const results = await Promise.all(
      plantsResult.plants.map(async (plant) => ({
        plant,
        operationsResult: await operations.getOperations(plant.id, {
          offset: 0,
          size: recentOperationCount,
        }),
      })),
    );
    if (version !== loadVersion) return;
    const loaded: GardenHistory[] = [];
    for (const { plant, operationsResult } of results) {
      if (operationsResult.kind !== "read") {
        setView("failed");
        return;
      }
      loaded.push({ kind: "garden", plant, page: operationsResult.page });
    }
    const display = () => {
      if (version !== loadVersion) return;
      setGardenHistories(loaded);
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

  // A reload can be superseded by a newer one; only the latest may report
  // failure, so capture the version before awaiting and re-check it on rejection.
  const guardReload = (reload: Promise<void>): Promise<void> => {
    const version = loadVersion;
    return reload.catch(() => {
      if (version === loadVersion) setView("failed");
    });
  };

  const refreshCurrentView = (animate = false): Promise<void> =>
    selected() === "garden" ? loadJournal(animate) : loadCemetery(true);

  const selectView = (next: PlantView, updateUrl = true) => {
    setSearchQuery("");
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
      void guardReload(loadCemetery());
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

  const catalogErrorMessage = (result: { kind: string }) =>
    result.kind === "unknownComponent"
      ? "Choose known substrate components."
      : result.kind === "catalogReadFailed"
        ? "The substrate catalog could not be read. Try again."
        : undefined;

  const createNewPlant = async (details: Journal.NewPlantDetails) => {
    setPlantSaveError(undefined);
    let result: Journal.CreatePlantResult;
    try {
      result = await props.plants.createPlant(details);
    } catch {
      setPlantSaveError("The plant could not be saved.");
      return;
    }
    if (result.kind !== "created") {
      setPlantSaveError(catalogErrorMessage(result) ?? "The plant could not be saved.");
      return;
    }
    setPlantSheetCompleted(true);
    setPlantTarget(undefined);
    selectView("garden");
    setCreationReloadFailed(false);
    await loadJournal().catch(() => setView("failed"));
    if (view() === "failed") setCreationReloadFailed(true);
    document.getElementById("garden-toggle")?.focus();
  };

  const editExistingPlant = async (plant: Journal.Plant, details: Journal.NewPlantDetails) => {
    setPlantSaveError(undefined);
    let result: Journal.EditPlantResult;
    try {
      result = await props.plants.editPlant(plant.id, details);
    } catch {
      setPlantSaveError("The plant could not be saved.");
      return;
    }
    if (result.kind !== "edited") {
      setPlantSaveError(
        catalogErrorMessage(result) ??
          (result.kind === "plantMissing"
            ? "This plant no longer exists."
            : result.kind === "plantArchived"
              ? "This plant is archived and can no longer be edited."
              : "The plant could not be saved."),
      );
      return;
    }
    setPlantTarget(undefined);
    await loadJournal().catch(() => setView("failed"));
    document.getElementById(editPlantControlId(plant.id))?.focus();
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
        const result = await props.operations.logOperation(target.plant, date, details);
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
    await guardReload(refreshCurrentView(target.kind === "log"));
    setOperationChange(
      editedOperation === undefined
        ? { kind: "logged" }
        : { kind: "edited", operation: editedOperation },
    );
    if (formTarget() === undefined) document.getElementById(operationControlId(target))?.focus();
  };

  const confirmDelete = async (operation: Journal.Operation) => {
    let result: Journal.DeleteOperationResult;
    try {
      result = await props.operations.deleteOperation(operation.id);
    } catch {
      return "The operation could not be deleted.";
    }
    if (result.kind === "operationMissing") return "This operation no longer exists.";
    if (result.kind === "cannotDeleteLatestRepot")
      return "This is the plant's current repot and cannot be deleted.";
    if (result.kind === "deleteFailed") return "The operation could not be deleted.";
    setDeleteCompleted(true);
    setDeleteTarget(undefined);
    setFormTarget(undefined);
    setSaveError(undefined);
    await guardReload(refreshCurrentView());
    setOperationChange({ kind: "deleted" });
    return undefined;
  };

  const substratesClient = createMemo(() => props.substrates);
  const pesticideCatalogClient = createMemo(() => props.pesticideCatalog);
  const substrateCatalog = createSubstrateCatalogController(
    substratesClient,
    setSubstrateComponents,
    setSubstrateMixes,
  );
  const {
    addSubstrateComponent,
    editSubstrateComponent,
    archiveSubstrateComponent,
    addSubstrateMix,
  } = substrateCatalog;
  const pesticideCatalog = createPesticideCatalogController(pesticideCatalogClient, setPesticides);
  const { addPesticide, editPesticide, archivePesticide } = pesticideCatalog;

  const requestDeleteSubstrateMix = (mix: Journal.SubstrateMix) => {
    setDeleteMixCompleted(false);
    setDeleteMixTarget(mix);
  };

  const confirmDeleteSubstrateMix = async (mix: Journal.SubstrateMix) => {
    const message = await substrateCatalog.deleteSubstrateMix(mix);
    if (message !== undefined) return message;
    setDeleteMixCompleted(true);
    setDeleteMixTarget(undefined);
    return undefined;
  };

  createEffect(() => {
    if (!ready() || initialViewLoaded) return;
    initialViewLoaded = true;
    if (selected() === "cemetery") {
      void guardReload(loadCemetery());
    }
  });

  onMount(() => {
    unsubscribeFeed = props.attention.subscribe((event) => {
      if (event.kind === "connectionState") {
        setFeedConnectionState(event.state);
      } else {
        const proj = event.projection;
        for (const id of recentlyArchived)
          if (!proj.plants.some((s) => s.plant === id)) recentlyArchived.delete(id);
        if (activePlantIdsLoaded) {
          if (!isAttentionProjectionValid(proj, activeIds, recentlyArchived)) {
            setView("failed");
            return;
          }
        }
        setAttentionProjection(proj);
        setLastAttentionUpdate(Date.now());
      }
    });
    onCleanup(() => {
      unsubscribeFeed?.();
    });

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
        connectionState={feedConnectionState()}
        lastUpdateAt={lastAttentionUpdate()}
        searchQuery={searchQuery()}
        onSelect={selectView}
        onAddPlant={() => {
          setPlantSaveError(undefined);
          setPlantSheetCompleted(false);
          setPlantTarget({ kind: "add" });
        }}
        onSearchQuery={setSearchQuery}
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
          <JournalList
            view={selected()}
            histories={filteredHistories()}
            emptyMessage={emptyMessage()}
            attentionProjection={attentionProjection()}
            substrateComponents={substrateComponents()}
            pesticides={pesticides()}
            getOperations={(plant, window) => props.operations.getOperations(plant, window)}
            operationChange={operationChange()}
            onViewPhotos={(plant) => {
              setPhotosTarget(plant);
            }}
            onLog={(plant) => {
              setSaveError(undefined);
              setFormTarget({ kind: "log", plant });
            }}
            onArchive={(plant) => {
              setArchiveCompleted(false);
              setArchiveTarget(plant);
            }}
            onEditPlant={(plant) => {
              setPlantSaveError(undefined);
              setPlantTarget({ kind: "edit", plant });
            }}
            onEdit={(operation) => {
              setSaveError(undefined);
              setFormTarget({ kind: "edit", operation });
            }}
          />
        </Match>
      </Switch>
      <Show when={plantTarget()} keyed>
        {(target) => (
          <PlantSheet
            target={target}
            components={substrateComponents()}
            substrateMixes={substrateMixes()}
            saveError={plantSaveError()}
            completed={plantSheetCompleted()}
            onSubmit={(details) =>
              target.kind === "add"
                ? createNewPlant(details)
                : editExistingPlant(target.plant, details)
            }
            onAddComponent={addSubstrateComponent}
            onEditComponent={editSubstrateComponent}
            onArchiveComponent={archiveSubstrateComponent}
            onAddSubstrateMix={addSubstrateMix}
            onRequestDeleteSubstrateMix={requestDeleteSubstrateMix}
            onCancel={() => {
              setPlantTarget(undefined);
            }}
            deleteMixConfirming={deleteMixTarget() !== undefined}
          />
        )}
      </Show>
      <Show when={formTarget()} keyed>
        {(target) => (
          <OperationSheet
            target={target}
            substrateComponents={substrateComponents()}
            substrateMixes={substrateMixes()}
            pesticides={pesticides()}
            saveError={saveError()}
            onSubmit={(details, date) => saveOperation(target, details, date)}
            onAddSubstrateComponent={addSubstrateComponent}
            onEditSubstrateComponent={editSubstrateComponent}
            onArchiveSubstrateComponent={archiveSubstrateComponent}
            onAddSubstrateMix={addSubstrateMix}
            onRequestDeleteSubstrateMix={requestDeleteSubstrateMix}
            onAddPesticide={addPesticide}
            onEditPesticide={editPesticide}
            onArchivePesticide={archivePesticide}
            onCancel={() => {
              setFormTarget(undefined);
            }}
            onDelete={
              target.kind === "edit"
                ? {
                    controlId: Controls.deleteOperationControlId(target.operation.id),
                    onClick: () => {
                      setDeleteCompleted(false);
                      setDeleteTarget(target.operation);
                    },
                  }
                : undefined
            }
            deleteConfirming={deleteTarget() !== undefined || deleteMixTarget() !== undefined}
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
      <Show when={photosTarget()} keyed>
        {(plant) => (
          <PlantPhotosSheet
            plant={plant}
            photos={props.photos}
            onCancel={() => {
              setPhotosTarget(undefined);
            }}
          />
        )}
      </Show>
      <Show when={deleteTarget()} keyed>
        {(operation) => (
          <DeleteOperationConfirmation
            operation={operation}
            completed={deleteCompleted()}
            onConfirm={() => confirmDelete(operation)}
            onCancel={() => {
              setDeleteTarget(undefined);
            }}
          />
        )}
      </Show>
      <Show when={deleteMixTarget()} keyed>
        {(mix) => (
          <DeleteSubstrateMixConfirmation
            mix={mix}
            completed={deleteMixCompleted()}
            onConfirm={() => confirmDeleteSubstrateMix(mix)}
            onCancel={() => {
              setDeleteMixTarget(undefined);
            }}
          />
        )}
      </Show>
    </main>
  );
};
