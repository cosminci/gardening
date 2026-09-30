import type * as Journal from "../domain/Journal";

export const plantDisplayName = (plant: Journal.Plant) =>
  plant.details.maybeNickname ?? plant.details.species;

export const actionLabels: Record<Journal.ActionType, string> = {
  watered: "Watered",
  showered: "Showered",
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
  id: Journal.SubstrateComponentId,
  components: readonly Journal.SubstrateComponent[],
) => components.find((component) => component.id === id)?.data.name ?? id;

export const pesticideLabel = (id: Journal.PesticideId, pesticides: readonly Journal.Pesticide[]) =>
  pesticides.find((pesticide) => pesticide.id === id)?.data.name ?? id;

export const operationKindLabel = (details: Journal.OperationDetails) =>
  details.kind === "care" ? "Care" : "Repot";

export const operationEditLabel = (
  operation: Journal.Operation,
  position: number,
  section: "recent" | "historical",
) =>
  `Edit ${section} ${operation.details.kind} operation ${String(position)} from ${
    section === "recent" ? formatRecentDate(operation.date) : formatLocalDate(operation.date)
  }`;

// `kind` is the row's semantic identity; `label` is only the display text. The
// cell picks its specialized rendering off `kind` so relabelling never breaks it.
export interface OperationDetailRow {
  readonly kind: "moisture" | "actions" | "pesticides" | "substrate";
  readonly label: string;
  readonly value: string;
}

export const operationDetailRows = (
  details: Journal.OperationDetails,
  components: readonly Journal.SubstrateComponent[],
  pesticides: readonly Journal.Pesticide[],
): readonly OperationDetailRow[] => {
  switch (details.kind) {
    case "care": {
      const actions = [...details.actions].filter((action) => action !== "noAction");
      const actionSummary =
        actions.length === 0
          ? "None recorded"
          : actions.map((action) => actionLabels[action]).join(", ");
      const rows: OperationDetailRow[] = [
        { kind: "moisture", label: "Moisture", value: moistureLabels[details.moisture] },
        { kind: "actions", label: "Actions", value: actionSummary },
      ];
      if (details.pesticides.size > 0) {
        const pesticideSummary = [...details.pesticides]
          .map((id) => pesticideLabel(id, pesticides))
          .join(", ");
        rows.push({ kind: "pesticides", label: "Pesticides", value: pesticideSummary });
      }
      return rows;
    }
    case "repot":
      return [
        {
          kind: "substrate",
          label: "Substrate",
          value: formatSubstrate(details.substrate, components),
        },
      ];
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
  return `${day}.${month}.${year}`;
};

export const formatLocalDateTime = (value: string) => {
  const date = new Date(value);
  const hours = String(date.getHours()).padStart(2, "0");
  const minutes = String(date.getMinutes()).padStart(2, "0");
  return `${formatLocalDate(value)} ${hours}:${minutes}`;
};

export const formatShortDate = (value: string) => {
  const date = new Date(value);
  const day = String(date.getDate()).padStart(2, "0");
  const month = String(date.getMonth() + 1).padStart(2, "0");
  return `${day}.${month}`;
};

export const formatRecentDate = (value: string) => {
  const date = new Date(value);
  const day = date.getDate();
  const suffix =
    day % 100 >= 11 && day % 100 <= 13 ? "th" : (["th", "st", "nd", "rd"][day % 10] ?? "th");
  const month = new Intl.DateTimeFormat("en", { month: "long" }).format(date);
  return `${String(day)}${suffix} of ${month}`;
};
