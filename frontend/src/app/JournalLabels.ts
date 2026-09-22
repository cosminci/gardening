import type * as Journal from "../domain/Journal";

export const plantDisplayName = (plant: Journal.Plant) =>
  plant.details.maybeNickname ?? plant.details.species;

export const actionLabels: Record<Journal.ActionType, string> = {
  watered: "Watered",
  fertilized: "Fertilized",
  pesticide: "Pesticide",
  pruned: "Pruned",
  noAction: "None",
};

export const moistureLabels: Record<Journal.MoistureLevel, string> = {
  wet: "Wet",
  moderatePlus: "Moderate +",
  moderateMinus: "Moderate -",
  dry: "Dry",
  noReading: "N/A",
};

export const pesticideTypeLabels: Record<Journal.PesticideType, string> = {
  fungicide: "Fungicide",
  insecticide: "Insecticide",
  treatment: "Treatment",
};

export const substrateComponentLabel = (
  componentId: Journal.SubstrateComponentId,
  components: readonly Journal.SubstrateComponent[],
) => components.find((component) => component.id === componentId)?.data.name ?? componentId;

export const pesticideLabel = (
  pesticideId: Journal.PesticideId,
  pesticides: readonly Journal.Pesticide[],
) => pesticides.find((pesticide) => pesticide.id === pesticideId)?.data.name ?? pesticideId;

export const formatSubstrate = (
  value: Journal.Substrate,
  components: readonly Journal.SubstrateComponent[],
) =>
  value
    .map((part) => `${substrateComponentLabel(part.component, components)} ${String(part.share)}%`)
    .join(", ");
