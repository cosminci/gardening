import type {
  ActionType,
  MoistureLevel,
  Plant,
  Substrate,
  SubstrateComponent,
} from "../domain/Journal";

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

export const substrateComponentLabels: Record<SubstrateComponent, string> = {
  kekkilaUniversal: "Kekkila universal peat",
  kekkilaEricaceous: "Kekkila ericaceous peat",
  perlite: "Perlite",
  pineBark: "Pine bark",
  sand3to5: "Sand 3-5 mm",
  sand4to8: "Sand 4-8 mm",
  leca: "LECA",
};

export const formatSubstrate = (value: Substrate) =>
  value
    .map((part) => `${substrateComponentLabels[part.component]} ${String(part.share)}%`)
    .join(", ");
