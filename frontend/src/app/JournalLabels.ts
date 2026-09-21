import type {
  ActionType,
  MoistureLevel,
  Pesticide,
  PesticideId,
  Plant,
  Substrate,
  SubstrateComponent,
  SubstrateComponentId,
} from "../domain/Journal";

export const plantDisplayName = (plant: Plant) =>
  plant.details.maybeNickname ?? plant.details.species;

export const actionLabels: Record<ActionType, string> = {
  watered: "Watered",
  fertilized: "Fertilized",
  pesticide: "Pesticide",
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

export const substrateComponentLabel = (
  componentId: SubstrateComponentId,
  components: readonly SubstrateComponent[],
) => components.find((component) => component.id === componentId)?.data.name ?? componentId;

export const pesticideLabel = (pesticideId: PesticideId, pesticides: readonly Pesticide[]) =>
  pesticides.find((pesticide) => pesticide.id === pesticideId)?.data.name ?? pesticideId;

export const formatSubstrate = (value: Substrate, components: readonly SubstrateComponent[]) =>
  value
    .map((part) => `${substrateComponentLabel(part.component, components)} ${String(part.share)}%`)
    .join(", ");
