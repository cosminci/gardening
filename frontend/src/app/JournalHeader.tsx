import { createSignal, onCleanup, Show } from "solid-js";
import type { Component } from "solid-js";
import type { FeedConnectionState } from "../domain/PlantAttention";

interface JournalHeaderProps {
  readonly loaded: boolean;
  readonly gardenCount: number;
  readonly cemeteryCount: number;
  readonly selected: "garden" | "cemetery";
  readonly connectionState: FeedConnectionState;
  readonly lastUpdateAt: number | undefined;
  readonly onSelect: (view: "garden" | "cemetery") => void;
  readonly onAddPlant: () => void;
}

const connectionStateLabel = (state: FeedConnectionState) => {
  switch (state) {
    case "connecting":
      return "Backend connecting";
    case "connected":
      return "Backend connected";
    case "disconnected":
      return "Backend disconnected";
  }
};

const updateAge = (lastUpdateAt: number | undefined, now: number) => {
  if (lastUpdateAt === undefined) return "Awaiting first update";
  const seconds = Math.max(0, Math.floor((now - lastUpdateAt) / 1000));
  if (seconds <= 5) return "Just updated";
  return `Updated ${String(seconds)}s ago`;
};

export const JournalHeader: Component<JournalHeaderProps> = (props) => {
  const [now, setNow] = createSignal(Date.now());
  const clock = setInterval(() => {
    setNow(Date.now());
  }, 1000);
  onCleanup(() => {
    clearInterval(clock);
  });

  return (
    <header class="masthead">
      <div class="masthead__heading">
        <h1>Plant Journal</h1>
      </div>
      <div class="masthead__actions">
        <span
          role="status"
          aria-label={connectionStateLabel(props.connectionState)}
          class="backend-status"
        >
          <span aria-hidden="true" class={`feed-status feed-status--${props.connectionState}`} />
          <span aria-hidden="true">Backend</span>
          <span aria-hidden="true" class="backend-status__age">
            {updateAge(props.lastUpdateAt, now())}
          </span>
        </span>
        <Show when={props.loaded}>
          <button
            id="add-plant"
            class="primary-action"
            type="button"
            onClick={() => {
              props.onAddPlant();
            }}
          >
            Add plant
          </button>
          <div class="plant-views" role="group" aria-label="Plant views">
            <button
              id="garden-toggle"
              class="plant-views__button"
              type="button"
              aria-pressed={props.selected === "garden"}
              aria-label={`Garden, ${String(props.gardenCount)} ${props.gardenCount === 1 ? "plant" : "plants"}`}
              onClick={() => {
                props.onSelect("garden");
              }}
            >
              <svg class="plant-views__icon" viewBox="0 0 32 32" aria-hidden="true">
                <path d="M16 2 5 19h7v4H7l9-18 9 18h-5v-4h7L16 2Zm-2 20h4v8h-4z" />
              </svg>
              <strong>{props.gardenCount}</strong>
              <span>Garden</span>
            </button>
            <button
              id="cemetery-toggle"
              class="plant-views__button"
              type="button"
              aria-pressed={props.selected === "cemetery"}
              aria-label={`Cemetery, ${String(props.cemeteryCount)} ${props.cemeteryCount === 1 ? "plant" : "plants"}`}
              onClick={() => {
                props.onSelect("cemetery");
              }}
            >
              <svg class="plant-views__icon" viewBox="0 0 32 32" aria-hidden="true">
                <path d="M14 2h4v9h9v4h-9v15h-4V15H5v-4h9z" />
              </svg>
              <strong>{props.cemeteryCount}</strong>
              <span>Cemetery</span>
            </button>
          </div>
        </Show>
      </div>
    </header>
  );
};
