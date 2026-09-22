import { For, Show } from "solid-js";
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
  const date = () => Labels.formatLocalDate(props.operation.date);

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
        <span class="operation__kind">{Labels.operationKindLabel(props.operation.details)}</span>
      </div>
      <dl class="operation__details">
        <For
          each={Labels.operationDetailRows(
            props.operation.details,
            props.substrateComponents,
            props.pesticides,
          )}
        >
          {(detail) => (
            <>
              <dt>{detail.label}</dt>
              <dd>{detail.value}</dd>
            </>
          )}
        </For>
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
