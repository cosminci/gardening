import * as Journal from "../../src/domain/Journal";
import type { PesticideClient } from "../../src/domain/PesticideCatalog";
import type { PlantAttentionClient } from "../../src/domain/PlantAttention";
import type { SubstrateComponentClient } from "../../src/domain/SubstrateComponentCatalog";

const perliteId = Journal.substrateComponentId("00000000-0000-4000-8000-000000000003");
const pineBarkId = Journal.substrateComponentId("00000000-0000-4000-8000-000000000004");
const substrateComponents: readonly Journal.SubstrateComponent[] = [
  { id: perliteId, data: { name: Journal.nomenclatureName("Perlite"), maybeInfo: null } },
  { id: pineBarkId, data: { name: Journal.nomenclatureName("Pine bark"), maybeInfo: null } },
];
const emptyAttentionResult: Journal.GetAttentionResult = {
  kind: "read",
  projection: { measuredAt: Journal.instant("2026-01-01T00:00:00Z"), plants: [] },
};

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
  getAttentionResults,
  getPlantsResults,
  getOperationsByPlantId = {},
  logOperationResult = { kind: "loggingFailed", reason: new Error("unexpected write") },
  editOperationResult = { kind: "editFailed", reason: new Error("unexpected write") },
  getSubstrateComponentsResult = { kind: "read", entries: substrateComponents },
  componentAddResult = { kind: "addFailed", reason: new Error("unexpected write") },
  componentEditResult = { kind: "editFailed", reason: new Error("unexpected write") },
  getPesticidesResult = { kind: "read", entries: [] },
  pesticideAddResult = { kind: "addFailed", reason: new Error("unexpected write") },
  pesticideEditResult = { kind: "editFailed", reason: new Error("unexpected write") },
  logged = [],
  edited = [],
  addedComponents = [],
  editedComponents = [],
  addedPesticides = [],
  editedPesticides = [],
  operationWindows = [],
}: {
  getAttentionResults?: readonly [Journal.GetAttentionResult, ...Journal.GetAttentionResult[]];
  getPlantsResults?: readonly [Journal.GetPlantsResult, ...Journal.GetPlantsResult[]];
  getOperationsByPlantId?: Readonly<
    Record<string, readonly [Journal.GetOperationsResult, ...Journal.GetOperationsResult[]]>
  >;
  logOperationResult?: Journal.LogOperationResult;
  editOperationResult?: Journal.EditOperationResult;
  getSubstrateComponentsResult?: Journal.CatalogReadResult<Journal.SubstrateComponent>;
  componentAddResult?: Journal.CatalogAddResult<Journal.SubstrateComponent>;
  componentEditResult?: Journal.CatalogEditResult<Journal.SubstrateComponent>;
  getPesticidesResult?: Journal.CatalogReadResult<Journal.Pesticide>;
  pesticideAddResult?: Journal.CatalogAddResult<Journal.Pesticide>;
  pesticideEditResult?: Journal.CatalogEditResult<Journal.Pesticide>;
  logged?: { plantId: string; date: Journal.Instant; details: Journal.OperationDetails }[];
  edited?: { operationId: string; details: Journal.OperationDetails }[];
  addedComponents?: Journal.SubstrateComponentData[];
  editedComponents?: {
    id: Journal.SubstrateComponentId;
    data: Journal.SubstrateComponentData;
  }[];
  addedPesticides?: Journal.PesticideData[];
  editedPesticides?: { id: Journal.PesticideId; data: Journal.PesticideData }[];
  operationWindows?: { plantId: Journal.PlantId; window: Journal.OperationWindow }[];
} = {}): Journal.JournalClient &
  PlantAttentionClient &
  SubstrateComponentClient &
  PesticideClient => {
  const attentionResponses = getAttentionResults ?? ([emptyAttentionResult] as const);
  let attentionReads = 0;
  let plantReads = 0;
  const plants = [ficus(), monstera()].filter(
    (plant) =>
      attentionResponses[0].kind === "read" &&
      attentionResponses[0].projection.plants.some((sample) => sample.plantId === plant.id),
  );
  const plantResponses = getPlantsResults ?? ([{ kind: "read", plants }] as const);
  const operationReads = new Map<string, number>();

  return {
    getPlants: () => Promise.resolve(queuedResult(plantResponses, plantReads++)),
    getArchivedCount: () => Promise.resolve({ kind: "read", count: 0 }),
    getOperationDates: () => Promise.resolve({ kind: "read", dates: { kind: "empty" } }),
    archivePlant: () =>
      Promise.resolve({ kind: "archiveFailed", reason: new Error("unexpected write") }),
    getAttention: () => {
      const read = attentionReads++;
      return Promise.resolve(queuedResult(attentionResponses, read));
    },
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
    getSubstrateComponents: () => Promise.resolve(getSubstrateComponentsResult),
    addSubstrateComponent: (data) => {
      addedComponents.push(data);
      return Promise.resolve(componentAddResult);
    },
    editSubstrateComponent: (id, data) => {
      editedComponents.push({ id, data });
      return Promise.resolve(componentEditResult);
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
  };
};

const queuedResult = <Result>(results: readonly [Result, ...Result[]], read: number): Result => {
  const result = results.at(Math.min(read, results.length - 1));
  if (result === undefined) throw new Error("queued result is empty");
  return result;
};
