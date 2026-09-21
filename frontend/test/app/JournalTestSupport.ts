import type {
  ActionType,
  CatalogAddResult,
  CatalogEditResult,
  CatalogReadResult,
  EditOperationResult,
  GetOperationsResult,
  GetPlantsResult,
  JournalClient,
  LogOperationResult,
  Operation,
  OperationDetails,
  Pesticide,
  PesticideData,
  PesticideId,
  Plant,
  SubstrateComponent,
  SubstrateComponentData,
  SubstrateComponentId,
} from "../../src/domain/Journal";
import {
  instant,
  location,
  nickname,
  nomenclatureName,
  note,
  operationId,
  percentage,
  plantId,
  substrateComponentId,
  species,
  substrate,
} from "../../src/domain/Journal";

const perliteId = substrateComponentId("00000000-0000-4000-8000-000000000003");
const pineBarkId = substrateComponentId("00000000-0000-4000-8000-000000000004");
const substrateComponents: readonly SubstrateComponent[] = [
  { id: perliteId, data: { name: nomenclatureName("Perlite"), maybeInfo: null } },
  { id: pineBarkId, data: { name: nomenclatureName("Pine bark"), maybeInfo: null } },
];

export const ficus = (): Plant => ({
  id: plantId("p1"),
  details: {
    species: species("Ficus lyrata"),
    maybeNickname: nickname("Fern"),
    location: location("Balcony"),
    substrate: substrate([{ component: perliteId, share: percentage(100) }]),
    status: "active",
  },
});

export const monstera = (): Plant => ({
  id: plantId("p2"),
  details: {
    species: species("Monstera deliciosa"),
    maybeNickname: null,
    location: location("Kitchen"),
    substrate: substrate([{ component: pineBarkId, share: percentage(40) }]),
    status: "active",
  },
});

export const care = (
  id: string,
  date: string,
  moisture: "dry" | "moderatePlus" | "wet",
  maybeNote: string | null = null,
  actions: ReadonlySet<ActionType> = new Set(["watered"]),
  pesticides: ReadonlySet<PesticideId> = new Set(),
): Operation => ({
  id: operationId(id),
  plantId: plantId("p1"),
  date: instant(date),
  details: {
    kind: "care",
    actions,
    pesticides,
    moisture,
    maybeNote: maybeNote === null ? null : note(maybeNote),
  },
});

export const repot = (id: string, date: string): Operation => ({
  id: operationId(id),
  plantId: plantId("p1"),
  date: instant(date),
  details: {
    kind: "repot",
    substrate: substrate([{ component: perliteId, share: percentage(100) }]),
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
  getPlantsResult?: GetPlantsResult;
  getOperationsByPlantId?: Readonly<
    Record<string, readonly [GetOperationsResult, ...GetOperationsResult[]]>
  >;
  logOperationResult?: LogOperationResult;
  editOperationResult?: EditOperationResult;
  getSubstrateComponentsResult?: CatalogReadResult<SubstrateComponent>;
  componentAddResult?: CatalogAddResult<SubstrateComponent>;
  componentEditResult?: CatalogEditResult<SubstrateComponent>;
  getPesticidesResult?: CatalogReadResult<Pesticide>;
  pesticideAddResult?: CatalogAddResult<Pesticide>;
  pesticideEditResult?: CatalogEditResult<Pesticide>;
  logged?: { plantId: string; details: OperationDetails }[];
  edited?: { operationId: string; details: OperationDetails }[];
  addedComponents?: SubstrateComponentData[];
  editedComponents?: {
    id: SubstrateComponentId;
    data: SubstrateComponentData;
  }[];
  addedPesticides?: PesticideData[];
  editedPesticides?: { id: PesticideId; data: PesticideData }[];
} = {}): JournalClient => {
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
