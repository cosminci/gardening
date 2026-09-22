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

export const operationKindLabel = (details: Journal.OperationDetails) =>
  details.kind === "care" ? "Care" : "Repot";

export const operationDetailRows = (
  details: Journal.OperationDetails,
  components: readonly Journal.SubstrateComponent[],
  pesticides: readonly Journal.Pesticide[],
): readonly { readonly label: string; readonly value: string }[] => {
  switch (details.kind) {
    case "care": {
      const actions = [...details.actions].filter((action) => action !== "noAction");
      const rows = [
        { label: "Moisture", value: moistureLabels[details.moisture] },
        {
          label: "Actions",
          value:
            actions.length === 0
              ? "None recorded"
              : actions.map((action) => actionLabels[action]).join(", "),
        },
      ];
      return details.pesticides.size === 0
        ? rows
        : [
            ...rows,
            {
              label: "Pesticides",
              value: [...details.pesticides].map((id) => pesticideLabel(id, pesticides)).join(", "),
            },
          ];
    }
    case "repot":
      return [{ label: "Substrate", value: formatSubstrate(details.substrate, components) }];
  }
};

export const formatSubstrate = (
  value: Journal.Substrate,
  components: readonly Journal.SubstrateComponent[],
) =>
  value
    .map((part) => `${substrateComponentLabel(part.component, components)} ${String(part.share)}%`)
    .join(", ");

export const formatLocalDate = (value: string) => {
  const date = new Date(value);
  const year = String(date.getFullYear());
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
};
