import { Show, createSignal, onMount } from "solid-js";
import type { Component } from "solid-js";
import * as Journal from "../domain/Journal";
import "./catalog-editor.css";

interface SaveSubstrateMixSheetProps {
  readonly onSave: (
    name: Journal.SubstrateMixName,
    maybeNotes: Journal.SubstrateMixNotes | null,
  ) => Promise<Journal.AddSubstrateMixResult>;
  readonly onClose: () => void;
}

export const SaveSubstrateMixSheet: Component<SaveSubstrateMixSheetProps> = (props) => {
  let panel!: HTMLElement;
  const [name, setName] = createSignal("");
  const [notes, setNotes] = createSignal("");
  const [error, setError] = createSignal<string>();
  const [submitting, setSubmitting] = createSignal(false);

  const save = async (): Promise<void> => {
    const trimmedName = name().trim();
    if (trimmedName === "") {
      setError("Enter a name for this mix.");
      return;
    }
    const trimmedNotes = notes().trim();
    setSubmitting(true);
    try {
      const result = await props.onSave(
        Journal.substrateMixName(trimmedName),
        trimmedNotes === "" ? null : Journal.substrateMixNotes(trimmedNotes),
      );
      if (result.kind === "added") {
        props.onClose();
        return;
      }
      const errors = {
        duplicateSubstrate: "A mix with these exact components and shares is already saved.",
        addFailed: "The mix could not be saved.",
      } satisfies Record<typeof result.kind, string>;
      setError(errors[result.kind]);
    } finally {
      setSubmitting(false);
    }
  };

  onMount(() => {
    panel.focus();
  });

  return (
    <section
      ref={(element) => {
        panel = element;
      }}
      class="catalog-editor"
      aria-label="Save substrate mix"
      tabIndex="-1"
    >
      <header class="catalog-editor__header">
        <h2>Save substrate mix</h2>
        <button
          class="icon-action sheet-collapse"
          type="button"
          aria-label="Collapse save mix editor"
          onClick={() => {
            props.onClose();
          }}
        >
          <span class="sheet-collapse__icon" aria-hidden="true" />
        </button>
      </header>
      <form
        class="catalog-editor__form"
        aria-label="Save substrate mix"
        onSubmit={(event) => {
          event.preventDefault();
          void save();
        }}
      >
        <label class="field">
          <span>Name</span>
          <input
            aria-label="Name"
            type="text"
            value={name()}
            onInput={(event) => {
              setName(event.currentTarget.value);
            }}
          />
        </label>
        <label class="field">
          <span>Notes</span>
          <textarea
            aria-label="Notes"
            rows="10"
            value={notes()}
            onInput={(event) => {
              setNotes(event.currentTarget.value);
            }}
          />
        </label>
        <Show when={error()}>{(message) => <p role="alert">{message()}</p>}</Show>
        <footer class="catalog-editor__actions">
          <button class="primary-action" type="submit" disabled={submitting()}>
            {submitting() ? "Saving…" : "Save"}
          </button>
        </footer>
      </form>
    </section>
  );
};
