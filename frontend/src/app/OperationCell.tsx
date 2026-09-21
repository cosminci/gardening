import { Show } from "solid-js";
import type { Component } from "solid-js";
import type { Operation, OperationDetails } from "../domain/Journal";
import { actionLabels, formatSubstrate, moistureLabels } from "./JournalLabels";
import { editOperationControlId } from "./OperationControlIds";

interface OperationCellProps {
  readonly operation: Operation;
  readonly position: number;
  readonly onEdit: () => void;
}

export const OperationCell: Component<OperationCellProps> = (props) => {
  const date = () => formatLocalDate(props.operation.date);

  return (
    <li class={`operation operation--${props.operation.details.kind}`}>
      <div class="operation__header">
        <time dateTime={props.operation.date}>{date()}</time>
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
      <button
        id={editOperationControlId(props.operation.id)}
        class="text-action"
        type="button"
        aria-label={`Edit ${props.operation.details.kind} operation ${String(props.position)} from ${date()}`}
        onClick={() => {
          props.onEdit();
        }}
      >
        Edit
      </button>
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
