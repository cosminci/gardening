import { For, Show, onMount } from "solid-js";
import type { Component } from "solid-js";
import type * as Journal from "../domain/Journal";
import { formatSubstrate } from "./JournalLabels";
import { deleteSubstrateMixControlId } from "./OperationControlIds";
import "./substrate-mix-sheet.css";

interface LoadSubstrateMixSheetProps {
  readonly mixes: readonly Journal.SubstrateMix[];
  readonly components: readonly Journal.SubstrateComponent[];
  readonly onLoad: (mix: Journal.SubstrateMix) => void;
  readonly onRequestDelete: (mix: Journal.SubstrateMix) => void;
  readonly onClose: () => void;
}

export const LoadSubstrateMixSheet: Component<LoadSubstrateMixSheetProps> = (props) => {
  let panel!: HTMLElement;

  const isAvailable = (mix: Journal.SubstrateMix) =>
    mix.substrate.every((part) =>
      props.components.some((component) => component.id === part.component),
    );

  onMount(() => {
    panel.focus();
  });

  return (
    <section
      id="load-substrate-mix-sheet"
      ref={(element) => {
        panel = element;
      }}
      class="substrate-mix-sheet"
      aria-label="Load substrate mix"
      tabIndex="-1"
    >
      <header class="substrate-mix-sheet__header">
        <h2>Load substrate mix</h2>
        <button
          class="icon-action sheet-collapse"
          type="button"
          aria-label="Collapse load mix editor"
          onClick={() => {
            props.onClose();
          }}
        >
          <span class="sheet-collapse__icon" aria-hidden="true" />
        </button>
      </header>
      <Show
        when={props.mixes.length > 0}
        fallback={<p class="substrate-mix-sheet__empty">No substrate mixes have been saved yet.</p>}
      >
        <ul class="substrate-mix-sheet__list">
          <For each={props.mixes}>
            {(mix) => (
              <li class="substrate-mix-card">
                <header class="substrate-mix-card__header">
                  <h3>{mix.name}</h3>
                  <button
                    id={deleteSubstrateMixControlId(mix.id)}
                    class="inline-icon-action inline-icon-action--delete"
                    type="button"
                    aria-label={`Delete ${mix.name}`}
                    onClick={() => {
                      props.onRequestDelete(mix);
                    }}
                  />
                </header>
                <dl class="substrate-mix-card__facts">
                  <dt>Substrate</dt>
                  <dd>{formatSubstrate(mix.substrate, props.components)}</dd>
                  <Show when={mix.maybeNotes}>
                    {(notes) => (
                      <>
                        <dt>Notes</dt>
                        <dd>{notes()}</dd>
                      </>
                    )}
                  </Show>
                </dl>
                <Show when={!isAvailable(mix)}>
                  <p class="inline-alert" role="alert">
                    One or more components in this mix are no longer available.
                  </p>
                </Show>
                <button
                  class="secondary-action"
                  type="button"
                  disabled={!isAvailable(mix)}
                  onClick={() => {
                    props.onLoad(mix);
                  }}
                >
                  Load
                </button>
              </li>
            )}
          </For>
        </ul>
      </Show>
    </section>
  );
};
