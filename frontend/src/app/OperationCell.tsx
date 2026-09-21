import { Show } from "solid-js";
import type { Component } from "solid-js";
import type {
  ActionType,
  MoistureLevel,
  Operation,
  OperationDetails,
  Substrate,
} from "../domain/Journal";

interface OperationCellProps {
  readonly operation: Operation;
}

export const OperationCell: Component<OperationCellProps> = (props) => {
  return (
    <li class={`operation operation--${props.operation.details.kind}`}>
      <div class="operation__header">
        <time dateTime={props.operation.date}>{formatLocalDate(props.operation.date)}</time>
        <span class="operation__kind">
          {props.operation.details.kind === "care" ? "Care" : "Repot"}
        </span>
      </div>
      <dl class="operation__details">
        {renderDetails(props.operation.details)}
        <Show when={props.operation.details.maybeNote}>
          {(note) => (
            <>
              <dt>Note</dt>
              <dd>{note()}</dd>
            </>
          )}
        </Show>
      </dl>
    </li>
  );
};

const renderDetails = (details: OperationDetails) => {
  switch (details.kind) {
    case "care":
      return (
        <>
          <dt>Moisture</dt>
          <dd>{moistureLabels[details.moisture]}</dd>
          <dt>Actions</dt>
          <dd>
            {details.actions.size === 0
              ? "None recorded"
              : [...details.actions].map((action) => actionLabels[action]).join(", ")}
          </dd>
        </>
      );
    case "repot":
      return (
        <>
          <dt>Substrate</dt>
          <dd>{formatSubstrate(details.substrate)}</dd>
        </>
      );
  }
};

const formatLocalDate = (value: string) => {
  const date = new Date(value);
  const year = String(date.getFullYear());
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
};

export const formatSubstrate = (value: Substrate) =>
  value.map((part) => `${componentLabels[part.component]} ${String(part.share)}%`).join(", ");

const actionLabels: Record<ActionType, string> = {
  watered: "Watered",
  fertilized: "Fertilized",
  pesticide: "Insecticide / H2O2",
  pruned: "Pruned",
  noAction: "None",
};
const moistureLabels: Record<MoistureLevel, string> = {
  wet: "Wet",
  moderatePlus: "Moderate +",
  moderateMinus: "Moderate -",
  dry: "Dry",
  noReading: "N/A",
};
const componentLabels = {
  kekkilaUniversal: "Kekkila universal peat",
  kekkilaEricaceous: "Kekkila ericaceous peat",
  perlite: "Perlite",
  pineBark: "Pine bark",
  sand3to5: "Sand 3-5 mm",
  sand4to8: "Sand 4-8 mm",
  leca: "LECA",
} as const;
