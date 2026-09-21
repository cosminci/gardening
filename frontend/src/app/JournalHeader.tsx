import { Show } from "solid-js";
import type { Component } from "solid-js";

interface JournalHeaderProps {
  readonly loaded: boolean;
  readonly plantCount: number;
}

export const JournalHeader: Component<JournalHeaderProps> = (props) => (
  <header class="masthead">
    <div>
      <h1>Plant journal</h1>
      <p class="masthead__summary">Care history, growing conditions, and repotting notes.</p>
    </div>
    <Show when={props.loaded}>
      <p class="plant-count">
        <strong>{props.plantCount}</strong>
        <span>active {props.plantCount === 1 ? "plant" : "plants"}</span>
      </p>
    </Show>
  </header>
);
