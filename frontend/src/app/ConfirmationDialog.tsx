import { Show, createSignal, onCleanup, onMount } from "solid-js";
import type { Component, JSX } from "solid-js";
import { useBackgroundBarrier } from "./BackgroundBarrier";
import "./confirmation-dialog.css";

export type ReturnFocus =
  | { readonly kind: "restoreOrFallback"; readonly expectedId: string; readonly fallbackId: string }
  | { readonly kind: "restoreOnCancelOnly"; readonly returnFocusId: string };

export interface ConfirmationDialogProps {
  readonly ariaLabel: string;
  readonly consequenceId: string;
  readonly title: JSX.Element;
  readonly consequence: JSX.Element;
  readonly confirmLabel: string;
  readonly completed: boolean;
  readonly returnFocus: ReturnFocus;
  readonly onConfirm: () => Promise<string | undefined>;
  readonly onCancel: () => void;
}

export const ConfirmationDialog: Component<ConfirmationDialogProps> = (props) => {
  let warning!: HTMLElement;
  let cancelButton!: HTMLButtonElement;
  let confirmButton!: HTMLButtonElement;
  const [pending, setPending] = createSignal(false);
  const [error, setError] = createSignal<string>();
  const previousFocus = document.activeElement;
  useBackgroundBarrier();

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
    window.addEventListener("keydown", onKeyDown);
    cancelButton.focus();
  });

  onCleanup(() => {
    window.removeEventListener("keydown", onKeyDown);
    const returnFocus = props.returnFocus;
    if (returnFocus.kind === "restoreOrFallback") {
      const target =
        !props.completed &&
        previousFocus?.isConnected === true &&
        previousFocus.id === returnFocus.expectedId
          ? previousFocus
          : document.getElementById(returnFocus.fallbackId);
      if (target instanceof HTMLElement) target.focus();
    } else {
      if (props.completed) return;
      const returnFocusId = returnFocus.returnFocusId;
      queueMicrotask(() => {
        document.getElementById(returnFocusId)?.focus();
      });
    }
  });

  return (
    <div class="confirmation-dialog-layer">
      <div class="confirmation-dialog-layer__scrim" aria-hidden="true" />
      <section
        ref={(element) => {
          warning = element;
        }}
        class="confirmation-dialog"
        role="alertdialog"
        aria-modal="true"
        aria-label={props.ariaLabel}
        aria-describedby={props.consequenceId}
        tabIndex={-1}
      >
        <span class="confirmation-dialog__warning" aria-hidden="true">
          !
        </span>
        <h2>{props.title}</h2>
        <p id={props.consequenceId}>{props.consequence}</p>
        <Show when={error()}>
          {(message) => (
            <p class="inline-alert" role="alert">
              {message()}
            </p>
          )}
        </Show>
        <div class="confirmation-dialog__actions">
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
            class="confirmation-dialog__commit"
            onClick={() => void confirm()}
          >
            {props.confirmLabel}
          </button>
        </div>
      </section>
    </div>
  );
};
