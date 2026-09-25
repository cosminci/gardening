import { Show, createSignal, onCleanup, onMount } from "solid-js";
import type { Component } from "solid-js";
import type * as Journal from "../domain/Journal";
import { archivePesticideControlId } from "./OperationControlIds";
import "./pesticide-archive-confirmation.css";

interface PesticideArchiveConfirmationProps {
  readonly pesticide: Journal.Pesticide;
  readonly completed: boolean;
  readonly onConfirm: () => Promise<string | undefined>;
  readonly onCancel: () => void;
}

export const PesticideArchiveConfirmation: Component<PesticideArchiveConfirmationProps> = (
  props,
) => {
  let warning!: HTMLElement;
  let cancelButton!: HTMLButtonElement;
  let confirmButton!: HTMLButtonElement;
  const [pending, setPending] = createSignal(false);
  const [error, setError] = createSignal<string>();
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
    if (props.completed) return;
    const returnFocusId = archivePesticideControlId(props.pesticide.id);
    queueMicrotask(() => {
      document.getElementById(returnFocusId)?.focus();
    });
  });

  return (
    <div class="pesticide-archive-confirmation-layer">
      <div class="pesticide-archive-confirmation-layer__scrim" aria-hidden="true" />
      <section
        ref={(element) => {
          warning = element;
        }}
        class="pesticide-archive-confirmation"
        role="alertdialog"
        aria-modal="true"
        aria-label={`Archive ${props.pesticide.data.name}`}
        aria-describedby="pesticide-archive-consequence"
        tabIndex={-1}
      >
        <span class="pesticide-archive-confirmation__warning" aria-hidden="true">
          !
        </span>
        <h2>Archive {props.pesticide.data.name}?</h2>
        <p id="pesticide-archive-consequence">
          This pesticide will no longer be offered for new operations. Operations that already
          reference it keep showing it. Archiving cannot be undone.
        </p>
        <Show when={error()}>
          {(message) => (
            <p class="inline-alert" role="alert">
              {message()}
            </p>
          )}
        </Show>
        <div class="pesticide-archive-confirmation__actions">
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
            class="pesticide-archive-confirmation__commit"
            onClick={() => void confirm()}
          >
            Archive permanently
          </button>
        </div>
      </section>
    </div>
  );
};
