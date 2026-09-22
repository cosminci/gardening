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

export const buildJournal = ({
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
}: {
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
} = {}): Journal.JournalClient => {
  const operationReads = new Map<string, number>();

  return {
    getPlants: () => Promise.resolve(getPlantsResult),
    getOperations: (id) => {
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
