import { Show, createSignal, onCleanup, onMount } from "solid-js";
import type { Component } from "solid-js";
import type * as Journal from "../domain/Journal";
import { deleteSubstrateMixControlId } from "./OperationControlIds";
import "./delete-substrate-mix-confirmation.css";

interface DeleteSubstrateMixConfirmationProps {
  readonly mix: Journal.SubstrateMix;
  readonly completed: boolean;
  readonly onConfirm: () => Promise<string | undefined>;
  readonly onCancel: () => void;
}

export const DeleteSubstrateMixConfirmation: Component<DeleteSubstrateMixConfirmationProps> = (
  props,
) => {
  let warning!: HTMLElement;
  let cancelButton!: HTMLButtonElement;
  let confirmButton!: HTMLButtonElement;
  const [pending, setPending] = createSignal(false);
  const [error, setError] = createSignal<string>();
  const previousFocus = document.activeElement;
  const background = [...document.querySelectorAll<HTMLElement>(".masthead, .journal")];

  const onKeyDown = (event: KeyboardEvent) => {
    if (event.key === "Escape" && !pending()) {
      event.preventDefault();
      props.onCancel();
    }
    if (event.key !== "Tab") return;
    if (pending()) {
      event.preventDefault();
      warning.focus();
      return;
    }
    if (event.shiftKey && document.activeElement === cancelButton) {
      event.preventDefault();
      confirmButton.focus();
    } else if (!event.shiftKey && document.activeElement === confirmButton) {
      event.preventDefault();
      cancelButton.focus();
    }
  };

  const confirm = async () => {
    warning.focus();
    setPending(true);
    const message = await props.onConfirm();
    if (message !== undefined) {
      setError(message);
      setPending(false);
      cancelButton.focus();
    }
  };

  onMount(() => {
    background.forEach((element) => {
      element.inert = true;
    });
    window.addEventListener("keydown", onKeyDown);
    cancelButton.focus();
  });

  onCleanup(() => {
    background.forEach((element) => {
      element.inert = false;
    });
    window.removeEventListener("keydown", onKeyDown);
    const expectedId = deleteSubstrateMixControlId(props.mix.id);
    const target =
      !props.completed && previousFocus?.isConnected === true && previousFocus.id === expectedId
        ? previousFocus
        : document.getElementById("load-substrate-mix-sheet");
    if (target instanceof HTMLElement) target.focus();
  });

  return (
    <div class="delete-substrate-mix-confirmation-layer">
      <div class="delete-substrate-mix-confirmation-layer__scrim" aria-hidden="true" />
      <section
        ref={(element) => {
          warning = element;
        }}
        class="delete-substrate-mix-confirmation"
        role="alertdialog"
        aria-modal="true"
        aria-label={`Delete ${props.mix.name}`}
        aria-describedby="delete-substrate-mix-consequence"
        tabIndex={-1}
      >
        <span class="delete-substrate-mix-confirmation__warning" aria-hidden="true">
          !
        </span>
        <h2>Delete {props.mix.name}?</h2>
        <p id="delete-substrate-mix-consequence">
          This removes the saved mix permanently. Deleting cannot be undone.
        </p>
        <Show when={error()}>
          {(message) => (
            <p class="inline-alert" role="alert">
              {message()}
            </p>
          )}
        </Show>
        <div class="delete-substrate-mix-confirmation__actions">
          <button
            ref={(element) => {
              cancelButton = element;
            }}
            type="button"
            disabled={pending()}
            onClick={() => {
              props.onCancel();
            }}
          >
            Cancel
          </button>
          <button
            ref={(element) => {
              confirmButton = element;
            }}
            type="button"
            disabled={pending()}
            class="delete-substrate-mix-confirmation__commit"
            onClick={() => void confirm()}
          >
            Delete permanently
          </button>
        </div>
      </section>
    </div>
  );
};
