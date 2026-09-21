import type {
  ActionType,
  EditOperationResult,
  GetOperationsResult,
  GetPlantsResult,
  JournalClient,
  LogOperationResult,
  Operation,
  OperationDetails,
  Plant,
} from "../../src/domain/Journal";
import {
  instant,
  location,
  nickname,
  note,
  operationId,
  percentage,
  plantId,
  species,
  substrate,
} from "../../src/domain/Journal";

export const ficus = (): Plant => ({
  id: plantId("p1"),
  details: {
    species: species("Ficus lyrata"),
    maybeNickname: nickname("Fern"),
    location: location("Balcony"),
    substrate: substrate([{ component: "perlite", share: percentage(100) }]),
    status: "active",
  },
});

export const monstera = (): Plant => ({
  id: plantId("p2"),
  details: {
    species: species("Monstera deliciosa"),
    maybeNickname: null,
    location: location("Kitchen"),
    substrate: substrate([{ component: "pineBark", share: percentage(40) }]),
    status: "active",
  },
});

export const care = (
  id: string,
  date: string,
  moisture: "dry" | "moderatePlus" | "wet",
  maybeNote: string | null = null,
  actions: ReadonlySet<ActionType> = new Set(["watered"]),
): Operation => ({
  id: operationId(id),
  plantId: plantId("p1"),
  date: instant(date),
  details: {
    kind: "care",
    actions,
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
    substrate: substrate([{ component: "perlite", share: percentage(100) }]),
    maybeNote: null,
  },
});

export const buildJournal = ({
  getPlantsResult = { kind: "read", plants: [] },
  getOperationsByPlantId = {},
  logOperationResult = { kind: "loggingFailed", reason: new Error("unexpected write") },
  editOperationResult = { kind: "editFailed", reason: new Error("unexpected write") },
  logged = [],
  edited = [],
}: {
  getPlantsResult?: GetPlantsResult;
  getOperationsByPlantId?: Readonly<
    Record<string, readonly [GetOperationsResult, ...GetOperationsResult[]]>
  >;
  logOperationResult?: LogOperationResult;
  editOperationResult?: EditOperationResult;
  logged?: { plantId: string; details: OperationDetails }[];
  edited?: { operationId: string; details: OperationDetails }[];
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
  };
};
