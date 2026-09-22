import * as Journal from "../../src/domain/Journal";

const perliteId = Journal.substrateComponentId("00000000-0000-4000-8000-000000000003");
const pineBarkId = Journal.substrateComponentId("00000000-0000-4000-8000-000000000004");
const substrateComponents: readonly Journal.SubstrateComponent[] = [
  { id: perliteId, data: { name: Journal.nomenclatureName("Perlite"), maybeInfo: null } },
  { id: pineBarkId, data: { name: Journal.nomenclatureName("Pine bark"), maybeInfo: null } },
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

export const unavailableAttention = (
  plant: Journal.Plant,
  sampleCount = 0,
): Journal.PlantAttention => ({
  plant,
  cadence: { kind: "unavailable", sampleCount, maybeElapsed: null },
});

export const inferredAttention = (
  plant: Journal.Plant,
  state: Journal.WateringState = "current",
  urgency: Journal.Urgency = { kind: "finite", numeratorNanos: "1", denominatorNanos: "1" },
): Journal.PlantAttention => ({
  plant,
  cadence: {
    kind: "inferred",
    sampleCount: 5,
    averageInterval: Journal.duration("PT24H"),
    elapsed: Journal.duration(
      state === "current" ? "PT12H" : state === "overdue" ? "PT25H" : "PT49H",
    ),
    urgency,
    state,
  },
});

export const attentionResult = (
  plants: readonly Journal.PlantAttention[] = [],
): Journal.GetAttentionResult => ({
  kind: "read",
  projection: { measuredAt: Journal.instant("2026-01-01T00:00:00Z"), plants },
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
  getPlantsResult = { kind: "read", plants: [] },
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
  getPlantsResult?: Journal.GetPlantsResult;
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
  logged?: { plantId: string; details: Journal.OperationDetails }[];
  edited?: { operationId: string; details: Journal.OperationDetails }[];
  addedComponents?: Journal.SubstrateComponentData[];
  editedComponents?: {
    id: Journal.SubstrateComponentId;
    data: Journal.SubstrateComponentData;
  }[];
  addedPesticides?: Journal.PesticideData[];
  editedPesticides?: { id: Journal.PesticideId; data: Journal.PesticideData }[];
  operationWindows?: { plantId: Journal.PlantId; window: Journal.OperationWindow }[];
} = {}): Journal.JournalClient => {
  const attentionResponses =
    getAttentionResults ??
    (getPlantsResult.kind === "read"
      ? ([
          attentionResult(getPlantsResult.plants.map((plant) => unavailableAttention(plant))),
        ] as const)
      : ([{ kind: "readFailed", reason: getPlantsResult.reason }] as const));
  let attentionReads = 0;
  const operationReads = new Map<string, number>();

  return {
    getAttention: () => {
      const read = attentionReads++;
      return Promise.resolve(attentionResponses[Math.min(read, attentionResponses.length - 1)]!);
    },
    getPlants: () => Promise.resolve(getPlantsResult),
    getOperations: (id, window) => {
      operationWindows.push({ plantId: id, window });
      const results = getOperationsByPlantId[id];
      if (results === undefined)
        return Promise.reject(new Error(`missing getOperations response for ${id}`));

      const read = operationReads.get(id) ?? 0;
      operationReads.set(id, read + 1);
      return Promise.resolve(results[Math.min(read, results.length - 1)]!);
    },
    logOperation: (id, details) => {
      logged.push({ plantId: id, details });
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
