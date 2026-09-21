import { For, Show } from "solid-js";
import type { Component } from "solid-js";
import type { Operation, Plant } from "../domain/Journal";
import { OperationCell, formatSubstrate } from "./OperationCell";
import "./plant-card.css";
import "./plant-history.css";

interface PlantCardProps {
  readonly plant: Plant;
  readonly operations: readonly Operation[];
}

export const PlantCard: Component<PlantCardProps> = (props) => {
  const name = () => props.plant.details.maybeNickname ?? props.plant.details.species;
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
            <span>No operations yet.</span> Add the first care entry.
          </p>
        }
      >
        <ol class="operation-list">
          <For each={recentOperations()}>
            {(operation) => <OperationCell operation={operation} />}
          </For>
        </ol>
      </Show>
    </article>
  );
};
