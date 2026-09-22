import { Show } from "solid-js";
import type { Component } from "solid-js";
import type * as Journal from "../domain/Journal";
import * as Labels from "./JournalLabels";
import { editOperationControlId } from "./OperationControlIds";

interface OperationCellProps {
  readonly operation: Journal.Operation;
  readonly position: number;
  readonly substrateComponents: readonly Journal.SubstrateComponent[];
  readonly pesticides: readonly Journal.Pesticide[];
  readonly onEdit: () => void;
}

export const OperationCell: Component<OperationCellProps> = (props) => {
  const date = () => formatLocalDate(props.operation.date);

  return (
    <li
      class={`operation operation--${props.operation.details.kind}`}
      style={{ "view-transition-name": `journal-operation-${props.operation.id}` }}
    >
      <div class="operation__header">
        <time dateTime={props.operation.date}>{date()}</time>
        <button
          id={editOperationControlId(props.operation.id)}
          class="operation__edit"
          type="button"
          aria-label={`Edit ${props.operation.details.kind} operation ${String(props.position)} from ${date()}`}
          onClick={() => {
            props.onEdit();
          }}
        >
          Edit
        </button>
        <span class="operation__kind">
          {props.operation.details.kind === "care" ? "Care" : "Repot"}
        </span>
      </div>
      <dl class="operation__details">
        {renderDetails(props.operation.details, props.substrateComponents, props.pesticides)}
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

const renderDetails = (
  details: Journal.OperationDetails,
  substrateComponents: readonly Journal.SubstrateComponent[],
  pesticides: readonly Journal.Pesticide[],
) => {
  switch (details.kind) {
    case "care": {
      const actions = [...details.actions].filter((action) => action !== "noAction");
      return (
        <>
          <dt>Moisture</dt>
          <dd>{Labels.moistureLabels[details.moisture]}</dd>
          <dt>Actions</dt>
          <dd>
            {actions.length === 0
              ? "None recorded"
              : actions.map((action) => Labels.actionLabels[action]).join(", ")}
          </dd>
          <Show when={details.pesticides.size > 0}>
            <>
              <dt>Pesticides</dt>
              <dd>
                {[...details.pesticides]
                  .map((id) => Labels.pesticideLabel(id, pesticides))
                  .join(", ")}
              </dd>
            </>
          </Show>
        </>
      );
    }
    case "repot":
      return (
        <>
          <dt>Substrate</dt>
          <dd>{Labels.formatSubstrate(details.substrate, substrateComponents)}</dd>
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
