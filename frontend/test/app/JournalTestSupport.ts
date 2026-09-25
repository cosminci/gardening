import * as Journal from "../../src/domain/Journal";
import type { OperationClient } from "../../src/domain/Operation";
import type { PesticideClient } from "../../src/domain/PesticideCatalog";
import type { PlantClient } from "../../src/domain/Plant";
import type {
  FeedConnectionState,
  FeedEvent,
  PlantAttentionFeed,
} from "../../src/domain/PlantAttention";
import type { SubstrateClient } from "../../src/domain/SubstrateCatalog";

const perliteId = Journal.substrateComponentId("00000000-0000-4000-8000-000000000003");
const pineBarkId = Journal.substrateComponentId("00000000-0000-4000-8000-000000000004");
const substrateComponents: readonly Journal.SubstrateComponent[] = [
  { id: perliteId, data: { name: Journal.substrateComponentName("Perlite"), maybeInfo: null } },
  { id: pineBarkId, data: { name: Journal.substrateComponentName("Pine bark"), maybeInfo: null } },
];

export const ficus = (): Journal.Plant => ({
  id: Journal.plantId("p1"),
  details: {
    species: Journal.species("Ficus lyrata"),
    maybeNickname: Journal.nickname("Fern"),
    location: Journal.location("Balcony"),
    substrate: Journal.substrate([{ component: perliteId, share: Journal.percentage(100) }]),
    status: "active",
  },
});

export const monstera = (): Journal.Plant => ({
  id: Journal.plantId("p2"),
  details: {
    species: Journal.species("Monstera deliciosa"),
    maybeNickname: null,
    location: Journal.location("Kitchen"),
    substrate: Journal.substrate([{ component: pineBarkId, share: Journal.percentage(40) }]),
    status: "active",
  },
});

export const care = ({
  id,
  date,
  moisture,
  maybeNote = null,
  actions = new Set(["watered"]),
  pesticides = new Set(),
}: {
  id: string;
  date: string;
  moisture: "dry" | "moderatePlus" | "wet";
  maybeNote?: string | null;
  actions?: ReadonlySet<Journal.ActionType>;
  pesticides?: ReadonlySet<Journal.PesticideId>;
}): Journal.Operation => ({
  id: Journal.operationId(id),
  plantId: Journal.plantId("p1"),
  date: Journal.instant(date),
  details: {
    kind: "care",
    actions,
    pesticides,
    moisture,
    maybeNote: maybeNote === null ? null : Journal.note(maybeNote),
  },
});

export const repot = (id: string, date: string): Journal.Operation => ({
  id: Journal.operationId(id),
  plantId: Journal.plantId("p1"),
  date: Journal.instant(date),
  details: {
    kind: "repot",
    substrate: Journal.substrate([{ component: perliteId, share: Journal.percentage(100) }]),
    maybeNote: null,
  },
});

export const operationsPage = (
  operations: readonly Journal.Operation[] = [],
  hasNextPage = false,
): Journal.GetOperationsResult => ({
  kind: "read",
  page: { operations, hasNextPage },
});

export const buildJournal = ({
  attentionProjection,
  getPlantsResults,
  getOperationsByPlantId = {},
  logOperationResult = { kind: "loggingFailed", reason: new Error("unexpected write") },
  editOperationResult = { kind: "editFailed", reason: new Error("unexpected write") },
  deleteOperationResult = { kind: "deleteFailed", reason: new Error("unexpected write") },
  getSubstrateComponentsResult = { kind: "read", entries: substrateComponents },
  componentAddResult = { kind: "addFailed", reason: new Error("unexpected write") },
  componentEditResult = { kind: "editFailed", reason: new Error("unexpected write") },
  getSubstrateMixesResult = { kind: "read", entries: [] },
  mixAddResult = { kind: "addFailed", reason: new Error("unexpected write") },
  mixDeleteResult = { kind: "deleteFailed", reason: new Error("unexpected write") },
  getPesticidesResult = { kind: "read", entries: [] },
  pesticideAddResult = { kind: "addFailed", reason: new Error("unexpected write") },
  pesticideEditResult = { kind: "editFailed", reason: new Error("unexpected write") },
  pesticideArchiveResult = { kind: "archiveFailed", reason: new Error("unexpected write") },
  logged = [],
  edited = [],
  deleted = [],
  addedComponents = [],
  editedComponents = [],
  addedMixes = [],
  deletedMixes = [],
  addedPesticides = [],
  editedPesticides = [],
  archivedPesticides = [],
  operationWindows = [],
}: {
  attentionProjection?: Journal.AttentionProjection;
  getPlantsResults?: readonly [Journal.GetPlantsResult, ...Journal.GetPlantsResult[]];
  getOperationsByPlantId?: Readonly<
    Record<string, readonly [Journal.GetOperationsResult, ...Journal.GetOperationsResult[]]>
  >;
  logOperationResult?: Journal.LogOperationResult;
  editOperationResult?: Journal.EditOperationResult;
  deleteOperationResult?: Journal.DeleteOperationResult;
  getSubstrateComponentsResult?: Journal.CatalogReadResult<Journal.SubstrateComponent>;
  componentAddResult?: Journal.CatalogAddResult<Journal.SubstrateComponent>;
  componentEditResult?: Journal.CatalogEditResult<Journal.SubstrateComponent>;
  getSubstrateMixesResult?: Journal.CatalogReadResult<Journal.SubstrateMix>;
  mixAddResult?: Journal.AddSubstrateMixResult;
  mixDeleteResult?: Journal.CatalogDeleteResult;
  getPesticidesResult?: Journal.CatalogReadResult<Journal.Pesticide>;
  pesticideAddResult?: Journal.CatalogAddResult<Journal.Pesticide>;
  pesticideEditResult?: Journal.PesticideEditResult;
  pesticideArchiveResult?: Journal.PesticideArchiveResult;
  logged?: { plantId: string; date: Journal.Instant; details: Journal.OperationDetails }[];
  edited?: { operationId: string; details: Journal.OperationDetails }[];
  deleted?: Journal.OperationId[];
  addedComponents?: Journal.SubstrateComponentData[];
  editedComponents?: {
    id: Journal.SubstrateComponentId;
    data: Journal.SubstrateComponentData;
  }[];
  addedMixes?: {
    name: Journal.SubstrateMixName;
    maybeNotes: Journal.SubstrateMixNotes | null;
    substrate: Journal.Substrate;
  }[];
  deletedMixes?: Journal.SubstrateMixId[];
  addedPesticides?: Journal.PesticideData[];
  editedPesticides?: { id: Journal.PesticideId; data: Journal.PesticideData }[];
  archivedPesticides?: Journal.PesticideId[];
  operationWindows?: { plantId: Journal.PlantId; window: Journal.OperationWindow }[];
} = {}): PlantClient &
  OperationClient &
  PlantAttentionFeed &
  SubstrateClient &
  PesticideClient & {
    pushAttention(projection: Journal.AttentionProjection): void;
    setAttentionConnection(state: FeedConnectionState): void;
  } => {
  let plantReads = 0;
  const defaultPlants = [ficus(), monstera()].filter(
    (plant) =>
      attentionProjection === undefined ||
      attentionProjection.plants.some((sample) => sample.plantId === plant.id),
  );
  const plantResponses = getPlantsResults ?? ([{ kind: "read", plants: defaultPlants }] as const);
  const operationReads = new Map<string, number>();
  const listeners: ((event: FeedEvent) => void)[] = [];

  const subscribe = (listener: (event: FeedEvent) => void) => {
    listeners.push(listener);
    listener({ kind: "connectionState", state: "connected" });
    if (attentionProjection !== undefined) {
      listener({ kind: "projection", projection: attentionProjection });
    }
    return () => {
      const idx = listeners.indexOf(listener);
      if (idx >= 0) listeners.splice(idx, 1);
    };
  };

  const pushAttention = (projection: Journal.AttentionProjection) => {
    for (const listener of [...listeners]) {
      listener({ kind: "projection", projection });
    }
  };

  const setAttentionConnection = (state: FeedConnectionState) => {
    for (const listener of [...listeners]) {
      listener({ kind: "connectionState", state });
    }
  };

  return {
    subscribe,
    pushAttention,
    setAttentionConnection,
    getPlants: () => Promise.resolve(queuedResult(plantResponses, plantReads++)),
    createPlant: () =>
      Promise.resolve({ kind: "createFailed", reason: new Error("unexpected write") }),
    getArchivedCount: () => Promise.resolve({ kind: "read", count: 0 }),
    getOperationDates: () => Promise.resolve({ kind: "read", dates: { kind: "empty" } }),
    archivePlant: () =>
      Promise.resolve({ kind: "archiveFailed", reason: new Error("unexpected write") }),
    editPlant: () => Promise.resolve({ kind: "editFailed", reason: new Error("unexpected write") }),
    getOperations: (id, window) => {
      operationWindows.push({ plantId: id, window });
      const results = getOperationsByPlantId[id];
      if (results === undefined)
        return Promise.reject(new Error(`missing getOperations response for ${id}`));

      const read = operationReads.get(id) ?? 0;
      operationReads.set(id, read + 1);
      return Promise.resolve(queuedResult(results, read));
    },
    logOperation: (id, date, details) => {
      logged.push({ plantId: id, date, details });
      return Promise.resolve(logOperationResult);
    },
    editOperation: (id, details) => {
      edited.push({ operationId: id, details });
      return Promise.resolve(editOperationResult);
    },
    deleteOperation: (id) => {
      deleted.push(id);
      return Promise.resolve(deleteOperationResult);
    },
    getSubstrateComponents: () => Promise.resolve(getSubstrateComponentsResult),
    addSubstrateComponent: (data) => {
      addedComponents.push(data);
      return Promise.resolve(componentAddResult);
    },
    editSubstrateComponent: (id, data) => {
      editedComponents.push({ id, data });
      return Promise.resolve(componentEditResult);
    },
    getSubstrateMixes: () => Promise.resolve(getSubstrateMixesResult),
    addSubstrateMix: (name, maybeNotes, substrate) => {
      addedMixes.push({ name, maybeNotes, substrate });
      return Promise.resolve(mixAddResult);
    },
    deleteSubstrateMix: (id) => {
      deletedMixes.push(id);
      return Promise.resolve(mixDeleteResult);
    },
    getPesticides: () => Promise.resolve(getPesticidesResult),
    addPesticide: (data) => {
      addedPesticides.push(data);
      return Promise.resolve(pesticideAddResult);
    },
    editPesticide: (id, data) => {
      editedPesticides.push({ id, data });
      return Promise.resolve(pesticideEditResult);
    },
    archivePesticide: (id) => {
      archivedPesticides.push(id);
      return Promise.resolve(pesticideArchiveResult);
    },
  };
};

const queuedResult = <Result>(results: readonly [Result, ...Result[]], read: number): Result => {
  const result = results.at(Math.min(read, results.length - 1));
  if (result === undefined) throw new Error("queued result is empty");
  return result;
};
