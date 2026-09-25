import { Show, createSignal, onCleanup, onMount } from "solid-js";
import type { Component } from "solid-js";
import type * as Journal from "../domain/Journal";
import { archiveSubstrateComponentControlId } from "./OperationControlIds";
import "./substrate-component-archive-confirmation.css";

interface SubstrateComponentArchiveConfirmationProps {
  readonly component: Journal.SubstrateComponent;
  readonly completed: boolean;
  readonly onConfirm: () => Promise<string | undefined>;
  readonly onCancel: () => void;
}

export const SubstrateComponentArchiveConfirmation: Component<
  SubstrateComponentArchiveConfirmationProps
> = (props) => {
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
    const returnFocusId = archiveSubstrateComponentControlId(props.component.id);
    queueMicrotask(() => {
      document.getElementById(returnFocusId)?.focus();
    });
  });

  return (
    <div class="substrate-component-archive-confirmation-layer">
      <div class="substrate-component-archive-confirmation-layer__scrim" aria-hidden="true" />
      <section
        ref={(element) => {
          warning = element;
        }}
        class="substrate-component-archive-confirmation"
        role="alertdialog"
        aria-modal="true"
        aria-label={`Archive ${props.component.data.name}`}
        aria-describedby="substrate-component-archive-consequence"
        tabIndex={-1}
      >
        <span class="substrate-component-archive-confirmation__warning" aria-hidden="true">
          !
        </span>
        <h2>Archive {props.component.data.name}?</h2>
        <p id="substrate-component-archive-consequence">
          This substrate component will no longer be offered for new plants or repots. Plants and
          operations that already reference it keep showing it. Archiving cannot be undone.
        </p>
        <Show when={error()}>
          {(message) => (
            <p class="inline-alert" role="alert">
              {message()}
            </p>
          )}
        </Show>
        <div class="substrate-component-archive-confirmation__actions">
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
            class="substrate-component-archive-confirmation__commit"
            onClick={() => void confirm()}
          >
            Archive permanently
          </button>
        </div>
      </section>
    </div>
  );
};
