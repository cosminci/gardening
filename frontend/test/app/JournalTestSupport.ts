import type {
  ActionType,
  GetOperationsResult,
  GetPlantsResult,
  JournalClient,
  Operation,
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
}: {
  getPlantsResult?: GetPlantsResult;
  getOperationsByPlantId?: Readonly<Record<string, GetOperationsResult>>;
} = {}): JournalClient => {
  return {
    getPlants: () => Promise.resolve(getPlantsResult),
    getOperations: (id) => {
      const result = getOperationsByPlantId[id];
      return result === undefined
        ? Promise.reject(new Error(`missing getOperations response for ${id}`))
        : Promise.resolve(result);
    },
    logOperation: () =>
      Promise.resolve({ kind: "loggingFailed", reason: new Error("unexpected write") }),
    editOperation: () =>
      Promise.resolve({ kind: "editFailed", reason: new Error("unexpected write") }),
  };
};
