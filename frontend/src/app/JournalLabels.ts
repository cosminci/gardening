import type {
  ActionType,
  MoistureLevel,
  Plant,
  Substrate,
  SubstrateComponentId,
} from "../domain/Journal";
import { seededSubstrateComponentIds } from "../domain/Journal";

export const plantDisplayName = (plant: Plant) =>
  plant.details.maybeNickname ?? plant.details.species;

export const actionLabels: Record<ActionType, string> = {
  watered: "Watered",
  fertilized: "Fertilized",
  pesticide: "Insecticide / H2O2",
  pruned: "Pruned",
  noAction: "None",
};

export const moistureLabels: Record<MoistureLevel, string> = {
  wet: "Wet",
  moderatePlus: "Moderate +",
  moderateMinus: "Moderate -",
  dry: "Dry",
  noReading: "N/A",
};

export const substrateComponentLabels: Record<SubstrateComponentId, string> = {
  [seededSubstrateComponentIds.kekkilaUniversal]: "Kekkila universal peat",
  [seededSubstrateComponentIds.kekkilaEricaceous]: "Kekkila ericaceous peat",
  [seededSubstrateComponentIds.perlite]: "Perlite",
  [seededSubstrateComponentIds.pineBark]: "Pine bark",
  [seededSubstrateComponentIds.sand3to5]: "Sand 3-5 mm",
  [seededSubstrateComponentIds.sand4to8]: "Sand 4-8 mm",
  [seededSubstrateComponentIds.leca]: "LECA",
};

export const substrateComponentLabel = (componentId: SubstrateComponentId) =>
  substrateComponentLabels[componentId] ?? componentId;

export const formatSubstrate = (value: Substrate) =>
  value
    .map((part) => `${substrateComponentLabel(part.component)} ${String(part.share)}%`)
    .join(", ");
