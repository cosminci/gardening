import { For, Show } from "solid-js";
import type { Component } from "solid-js";
import type { ActionType, MoistureLevel, Operation, Plant, Substrate } from "../domain/Journal";
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
      <header class="plant-card__header">
        <div class="plant-card__identity">
          <span class="plant-card__monogram" aria-hidden="true">
            {name().slice(0, 1)}
          </span>
          <div>
            <p class="eyebrow">{props.plant.details.location}</p>
            <h2>{name()}</h2>
            <p class="plant-card__species">{props.plant.details.species}</p>
          </div>
        </div>
      </header>
      <dl class="plant-facts">
        <dt>Substrate</dt>
        <dd>{formatSubstrate(props.plant.details.substrate)}</dd>
      </dl>
      <div class="history-heading">
        <h3>Recent care</h3>
        <span>Latest three entries</span>
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
            {(operation) => (
              <li class={`operation operation--${operation.details.kind}`}>
                <span class="operation__summary">{formatOperation(operation)}</span>
              </li>
            )}
          </For>
        </ol>
      </Show>
    </article>
  );
};

const formatOperation = (operation: Operation) => {
  const date = operation.date.slice(0, 10);
  const note = operation.details.maybeNote === null ? "" : ` — ${operation.details.maybeNote}`;
  if (operation.details.kind === "repot")
    return `${date} — Repotted — ${formatSubstrate(operation.details.substrate)}${note}`;
  const actions = [...operation.details.actions].map((action) => actionLabels[action]).join(", ");
  return `${date} — ${moistureLabels[operation.details.moisture]} — ${actions}${note}`;
};

const formatSubstrate = (value: Substrate) =>
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
