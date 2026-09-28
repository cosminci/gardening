import { For, Match, Show, Switch, untrack } from "solid-js";
import type { Component } from "solid-js";
import type * as Journal from "../domain/Journal";
import * as Labels from "./JournalLabels";
import { editOperationControlId } from "./OperationControlIds";
import { MoistureGauge } from "./MoistureGauge";
import { ActionGlyph, ActionIcons } from "./ActionIcons";
import { InfoControl } from "./InfoControl";

interface OperationCellProps {
  readonly operation: Journal.Operation;
  readonly position: number;
  readonly section: "recent" | "historical";
  readonly substrateComponents: readonly Journal.SubstrateComponent[];
  readonly pesticides: readonly Journal.Pesticide[];
  readonly onEdit: () => void;
}

export const OperationCell: Component<OperationCellProps> = (props) => {
  const date = () => Labels.formatRecentDate(props.operation.date);
  const shortDate = () => Labels.formatShortDate(props.operation.date);
  const careDetails = untrack(() =>
    props.operation.details.kind === "care" ? props.operation.details : undefined,
  );
  const moisture = careDetails?.moisture;
  const actions = careDetails?.actions;
  const pesticideNames = () =>
    careDetails && careDetails.pesticides.size > 0
      ? [...careDetails.pesticides]
          .map((id) => Labels.pesticideLabel(id, props.pesticides))
          .join(", ")
      : null;

  return (
    <li
      class={`operation operation--${props.operation.details.kind}`}
      style={{ "view-transition-name": `journal-operation-${props.operation.id}` }}
    >
      <div class="operation__header">
        <time dateTime={props.operation.date}>
          <span class="operation__date-long">{date()}</span>
          <span class="operation__date-short">{shortDate()}</span>
        </time>
        <button
          id={editOperationControlId(props.operation.id)}
          class="inline-icon-action inline-icon-action--edit"
          type="button"
          aria-label={Labels.operationEditLabel(props.operation, props.position, props.section)}
          onClick={() => {
            props.onEdit();
          }}
        />
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
            <Switch
              fallback={
                <>
                  <dt>{detail.label}</dt>
                  <dd>{detail.value}</dd>
                </>
              }
            >
              <Match when={detail.label === "Moisture" && moisture}>
                {(level) => (
                  <>
                    <dt class="operation__detail-label--compact">{detail.label}</dt>
                    <dd class="operation__detail-value--compact">
                      <MoistureGauge level={level()} />
                      <span class="operation__detail-value--compact-text">{detail.value}</span>
                    </dd>
                  </>
                )}
              </Match>
              <Match when={detail.label === "Actions" && actions}>
                {(actionSet) => (
                  <>
                    <dt class="operation__detail-label--compact">{detail.label}</dt>
                    <dd class="operation__detail-value--compact">
                      <span class="action-icons">
                        <ActionIcons
                          actions={
                            new Set([...actionSet()].filter((action) => action !== "pesticide"))
                          }
                        />
                        <Show when={actionSet().has("pesticide")}>
                          <InfoControl
                            id={`pesticides-info-${props.operation.id}`}
                            label="Pesticides applied"
                            notes={pesticideNames()}
                            trigger={
                              <svg
                                class="action-icon action-icon--pesticide"
                                viewBox="0 0 24 24"
                                aria-hidden="true"
                              >
                                <ActionGlyph type="pesticide" />
                              </svg>
                            }
                          />
                        </Show>
                      </span>
                      <span class="operation__detail-value--compact-text">{detail.value}</span>
                    </dd>
                  </>
                )}
              </Match>
              <Match when={detail.label === "Pesticides"}>
                <dt class="operation__detail-label--compact">{detail.label}</dt>
                <dd class="operation__detail-value--compact">
                  <span class="operation__detail-value--compact-text">{detail.value}</span>
                </dd>
              </Match>
            </Switch>
          )}
        </For>
        <Show when={props.operation.details.maybeNote}>
          {(note) => <dd class="operation__note-value">{note()}</dd>}
        </Show>
      </dl>
    </li>
  );
};
