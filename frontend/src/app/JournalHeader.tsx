import { Show } from "solid-js";
import type { Component } from "solid-js";

interface JournalHeaderProps {
  readonly loaded: boolean;
  readonly gardenCount: number;
  readonly cemeteryCount: number;
  readonly selected: "garden" | "cemetery";
  readonly onSelect: (view: "garden" | "cemetery") => void;
}

export const JournalHeader: Component<JournalHeaderProps> = (props) => (
  <header class="masthead">
    <h1>Plant Journal</h1>
    <Show when={props.loaded}>
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
  </header>
);
