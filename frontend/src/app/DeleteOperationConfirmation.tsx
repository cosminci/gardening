import { Show, createSignal, onCleanup, onMount } from "solid-js";
import type { Component } from "solid-js";
import type * as Journal from "../domain/Journal";
import * as Labels from "./JournalLabels";
import { deleteOperationControlId } from "./OperationControlIds";
import "./delete-operation-confirmation.css";

interface DeleteOperationConfirmationProps {
  readonly operation: Journal.Operation;
  readonly completed: boolean;
  readonly onConfirm: () => Promise<string | undefined>;
  readonly onCancel: () => void;
}

export const DeleteOperationConfirmation: Component<DeleteOperationConfirmationProps> = (props) => {
  let warning!: HTMLElement;
  let cancelButton!: HTMLButtonElement;
  let confirmButton!: HTMLButtonElement;
  const [pending, setPending] = createSignal(false);
  const [error, setError] = createSignal<string>();
  const background = [...document.querySelectorAll<HTMLElement>(".masthead, .journal")];
  const label = () =>
    `Delete this ${Labels.operationKindLabel(props.operation.details)} operation from ${Labels.formatLocalDate(props.operation.date)}`;

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
    if (props.completed) return;
    const returnFocusId = deleteOperationControlId(props.operation.id);
    queueMicrotask(() => {
      document.getElementById(returnFocusId)?.focus();
    });
  });

  return (
    <div class="delete-operation-confirmation-layer">
      <div class="delete-operation-confirmation-layer__scrim" aria-hidden="true" />
      <section
        ref={(element) => {
          warning = element;
        }}
        class="delete-operation-confirmation"
        role="alertdialog"
        aria-modal="true"
        aria-label={label()}
        aria-describedby="delete-operation-consequence"
        tabIndex={-1}
      >
        <span class="delete-operation-confirmation__warning" aria-hidden="true">
          !
        </span>
        <h2>{label()}?</h2>
        <p id="delete-operation-consequence">
          This removes the operation from the plant's history permanently. Deleting cannot be
          undone.
        </p>
        <Show when={error()}>
          {(message) => (
            <p class="inline-alert" role="alert">
              {message()}
            </p>
          )}
        </Show>
        <div class="delete-operation-confirmation__actions">
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
            class="delete-operation-confirmation__commit"
            onClick={() => void confirm()}
          >
            Delete permanently
          </button>
        </div>
      </section>
    </div>
  );
};
