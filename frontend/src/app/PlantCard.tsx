import { For, Show } from "solid-js";
import type { Component, JSX } from "solid-js";
import type * as Journal from "../domain/Journal";
import { InfoControl } from "./InfoControl";
import { formatLocalDate, formatSubstrate, plantDisplayName } from "./JournalLabels";
import { logOperationControlId } from "./OperationControlIds";
import { OperationCell } from "./OperationCell";
import { OperationHistory, type OperationHistoryChange } from "./OperationHistory";
import { editPlantControlId } from "./PlantSheet";

export const plantPhotosControlId = (plantId: Journal.PlantId) => `plant-photos-${String(plantId)}`;
import "./plant-card.css";
import "./plant-history.css";

interface PlantCardBaseProps {
  readonly operationPage: Journal.OperationPage;
  readonly substrateComponents: readonly Journal.SubstrateComponent[];
  readonly pesticides: readonly Journal.Pesticide[];
  readonly getOperations: (window: Journal.OperationWindow) => Promise<Journal.GetOperationsResult>;
  readonly operationChange: OperationHistoryChange | undefined;
  readonly onEdit: (operation: Journal.Operation) => void;
  readonly onViewPhotos: (plant: Journal.Plant) => void;
}

type PlantCardProps = PlantCardBaseProps &
  (
    | {
        readonly kind?: "garden";
        readonly plant: Journal.Plant;
        readonly watering?: Journal.WateringAttention | undefined;
        readonly measuredAt?: Journal.Instant | undefined;
        readonly onLog: () => void;
        readonly onArchive: () => void;
        readonly onEditPlant: (plant: Journal.Plant) => void;
      }
    | {
        readonly kind: "cemetery";
        readonly plant: Journal.Plant;
        readonly dates: Journal.OperationDates;
      }
  );

interface WateringPresentation {
  readonly label: string;
  readonly symbol: string;
  readonly delta: string;
  readonly details: JSX.Element;
}

const hour = 60n * 60n * 1000n;
const minute = 60_000;

const formatDuration = (duration: bigint) => {
  const roundedHours = (duration + hour - 1n) / hour;
  const days = roundedHours / 24n;
  const hours = roundedHours % 24n;
  return days > 0n
    ? `${String(days)}d${hours > 0n ? `${String(hours)}h` : ""}`
    : `${String(hours)}h`;
};

const formatAverageInterval = (duration: bigint) => {
  const roundedHours = (duration + hour - 1n) / hour;
  const days = roundedHours / 24n;
  const hours = roundedHours % 24n;
  const dayDescription = days === 1n ? "1 day" : `${String(days)} days`;
  const hourDescription = hours === 1n ? "1 hour" : `${String(hours)} hours`;
  return days === 0n
    ? hourDescription
    : hours === 0n
      ? dayDescription
      : `${dayDescription} and ${hourDescription}`;
};

const formatEvaluationAge = (measuredAt: Journal.Instant) => {
  const minutesAgo = Math.max(
    0,
    Math.floor((Date.now() - new Date(measuredAt).getTime()) / minute),
  );
  return `${String(minutesAgo)} ${minutesAgo === 1 ? "minute" : "minutes"} ago`;
};

const wateringPresentation = (
  watering: Journal.WateringAttention,
  measuredAt: Journal.Instant,
): WateringPresentation => {
  const evaluationAge = formatEvaluationAge(measuredAt);
  const details = (averageInterval: string) => (
    <dl class="watering-attention__details">
      <div>
        <dt>Watering operations</dt>
        <dd>{String(watering.sampleCount)} considered</dd>
      </div>
      <div>
        <dt>Average interval</dt>
        <dd>{averageInterval}</dd>
      </div>
      <div>
        <dt>Evaluated</dt>
        <dd>{evaluationAge}</dd>
      </div>
    </dl>
  );

  switch (watering.kind) {
    case "unavailable":
      return {
        label: "Watering cadence unavailable",
        symbol: "?",
        delta: "",
        details: (
          <p class="watering-attention__unavailable-details">Insufficient watering operations.</p>
        ),
      };
    case "current":
      return {
        label: "Watering current",
        symbol: "✓",
        delta: `in ${formatDuration(watering.averageInterval - watering.elapsed)}`,
        details: details(formatAverageInterval(watering.averageInterval)),
      };
    case "overdue":
      return {
        label: "Watering overdue",
        symbol: "!",
        delta: `late ${formatDuration(watering.elapsed - watering.averageInterval)}`,
        details: details(formatAverageInterval(watering.averageInterval)),
      };
    case "redAlert":
      return {
        label: "Watering red alert",
        symbol: "×",
        delta: `late ${formatDuration(watering.elapsed - watering.averageInterval)}`,
        details: details(formatAverageInterval(watering.averageInterval)),
      };
  }
};

const WateringStatus: Component<{
  watering: Journal.WateringAttention;
  measuredAt: Journal.Instant;
  plantName: string;
  plantId: Journal.PlantId;
}> = (props) => {
  const presentation = () => wateringPresentation(props.watering, props.measuredAt);
  const detailsId = () => `watering-attention-${props.plantId}`;

  return (
    <aside class={`watering-attention watering-attention--${props.watering.kind}`}>
      <span class="watering-attention__icon" aria-label={presentation().label}>
        <span aria-hidden="true">{presentation().symbol}</span>
      </span>
      <span class="watering-attention__delta">{presentation().delta}</span>
      <InfoControl
        id={detailsId()}
        label={`Watering attention details for ${props.plantName}`}
        notes={presentation().details}
      />
    </aside>
  );
};

export const PlantCard: Component<PlantCardProps> = (props) => {
  const name = () => plantDisplayName(props.plant);
  const recentOperations = () => [...props.operationPage.operations].reverse();

  return (
    <article
      class="plant-card"
      classList={{ "plant-card--archived": props.kind === "cemetery" }}
      aria-label={name()}
    >
      <div class="plant-card__row">
        {props.kind === "cemetery" ? (
          <aside class="recorded-life" aria-label={`Recorded care dates for ${name()}`}>
            <span class="recorded-life__stone" aria-hidden="true">
              RIP
            </span>
            {props.dates.kind === "recorded" ? (
              <span class="recorded-life__dates">
                <time dateTime={props.dates.first}>{formatLocalDate(props.dates.first)}</time>
                <span aria-hidden="true">–</span>
                <time dateTime={props.dates.last}>{formatLocalDate(props.dates.last)}</time>
              </span>
            ) : (
              <span class="recorded-life__dates">Dates unknown</span>
            )}
          </aside>
        ) : props.watering !== undefined && props.measuredAt !== undefined ? (
          <WateringStatus
            watering={props.watering}
            measuredAt={props.measuredAt}
            plantName={name()}
            plantId={props.plant.id}
          />
        ) : (
          <aside class="attention-pending" aria-label={`Attention pending for ${name()}`}>
            <span class="attention-pending__icon" aria-hidden="true" />
          </aside>
        )}
        <div class="plant-summary">
          <header class="plant-card__header">
            <div>
              <p class="eyebrow">{props.plant.details.location}</p>
              <h2>{name()}</h2>
              <p class="plant-card__species">{props.plant.details.species}</p>
            </div>
            <div class="plant-card__actions">
              <button
                id={plantPhotosControlId(props.plant.id)}
                class="plant-photos"
                type="button"
                aria-label={`Photos for ${name()}`}
                title={`Photos for ${name()}`}
                onClick={() => {
                  props.onViewPhotos(props.plant);
                }}
              >
                Photos
              </button>
              {props.kind !== "cemetery" && (
                <>
                  <button
                    id={editPlantControlId(props.plant.id)}
                    class="edit-plant"
                    type="button"
                    aria-label={`Edit ${name()}`}
                    title={`Edit ${name()}`}
                    onClick={() => {
                      props.onEditPlant(props.plant);
                    }}
                  >
                    Edit
                  </button>
                  <button
                    id={`archive-plant-${props.plant.id}`}
                    class="archive-plant"
                    type="button"
                    aria-label={`Archive ${name()}`}
                    title={`Archive ${name()}`}
                    onClick={() => {
                      props.onArchive();
                    }}
                  >
                    Archive
                  </button>
                </>
              )}
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
        {props.kind !== "cemetery" && (
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
        )}
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
