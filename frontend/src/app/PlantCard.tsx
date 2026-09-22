import { For, Show } from "solid-js";
import type { Component } from "solid-js";
import type * as Journal from "../domain/Journal";
import { formatSubstrate, plantDisplayName } from "./JournalLabels";
import { logOperationControlId } from "./OperationControlIds";
import { OperationCell } from "./OperationCell";
import { OperationHistory, type OperationHistoryChange } from "./OperationHistory";
import "./plant-card.css";
import "./plant-history.css";

interface PlantCardProps {
  readonly plant: Journal.Plant;
  readonly operationPage: Journal.OperationPage;
  readonly substrateComponents: readonly Journal.SubstrateComponent[];
  readonly pesticides: readonly Journal.Pesticide[];
  readonly getOperations: (window: Journal.OperationWindow) => Promise<Journal.GetOperationsResult>;
  readonly operationChange: OperationHistoryChange | undefined;
  readonly onLog: () => void;
  readonly onEdit: (operation: Journal.Operation) => void;
}

export const PlantCard: Component<PlantCardProps> = (props) => {
  const name = () => plantDisplayName(props.plant);
  const recentOperations = () => [...props.operationPage.operations].reverse();

  return (
    <article class="plant-card" aria-label={name()}>
      <div class="plant-card__row">
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
            <dd>{formatSubstrate(props.plant.details.substrate, props.substrateComponents)}</dd>
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
                  substrateComponents={props.substrateComponents}
                  pesticides={props.pesticides}
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
      </div>
      <Show when={props.operationPage.hasNextPage}>
        <OperationHistory
          plantId={props.plant.id}
          substrateComponents={props.substrateComponents}
          pesticides={props.pesticides}
          getOperations={props.getOperations}
          operationChange={props.operationChange}
          onEdit={props.onEdit}
        />
      </Show>
    </article>
  );
};
