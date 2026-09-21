import { For, Show } from "solid-js";
import type { Component } from "solid-js";
import type { Operation, Plant } from "../domain/Journal";
import { formatSubstrate, plantDisplayName } from "./JournalLabels";
import { logOperationControlId } from "./OperationControlIds";
import { OperationCell } from "./OperationCell";
import "./plant-card.css";
import "./plant-history.css";

interface PlantCardProps {
  readonly plant: Plant;
  readonly operations: readonly Operation[];
  readonly onLog: () => void;
  readonly onEdit: (operation: Operation) => void;
}

export const PlantCard: Component<PlantCardProps> = (props) => {
  const name = () => plantDisplayName(props.plant);
  const recentOperations = () =>
    [...props.operations]
      .sort((first, second) => Date.parse(first.date) - Date.parse(second.date))
      .slice(-3);

  return (
    <article class="plant-card" aria-label={name()}>
      <div class="plant-summary">
        <header class="plant-card__header">
          <div>
            <p class="eyebrow">{props.plant.details.location}</p>
            <h2>{name()}</h2>
            <p class="plant-card__species">{props.plant.details.species}</p>
          </div>
        </header>
        <dl class="plant-facts">
          <dt>Substrate</dt>
          <dd>{formatSubstrate(props.plant.details.substrate)}</dd>
        </dl>
      </div>
      <Show
        when={recentOperations().length > 0}
        fallback={
          <p class="empty-history">
            <span>No operations yet.</span>
          </p>
        }
      >
        <ol class="operation-list" aria-label="Recent operations" role="list">
          <For each={recentOperations()}>
            {(operation, index) => (
              <OperationCell
                operation={operation}
                position={index() + 1}
                onEdit={() => {
                  props.onEdit(operation);
                }}
              />
            )}
          </For>
        </ol>
      </Show>
      <button
        id={logOperationControlId(props.plant.id)}
        class="add-operation"
        type="button"
        aria-label={`Log operation for ${name()}`}
        title={`Add operation for ${name()}`}
        onClick={() => {
          props.onLog();
        }}
      >
        <span aria-hidden="true">+</span>
      </button>
    </article>
  );
};
