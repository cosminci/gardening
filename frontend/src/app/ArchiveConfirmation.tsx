import { Show, createSignal, onCleanup, onMount } from "solid-js";
import type { Component } from "solid-js";
import type * as Journal from "../domain/Journal";
import { plantDisplayName } from "./JournalLabels";
import "./archive-confirmation.css";

interface ArchiveConfirmationProps {
  readonly plant: Journal.Plant;
  readonly completed: boolean;
  readonly onConfirm: () => Promise<string | undefined>;
  readonly onCancel: () => void;
}

export const ArchiveConfirmation: Component<ArchiveConfirmationProps> = (props) => {
  const name = () => plantDisplayName(props.plant);
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
    const target =
      !props.completed &&
      previousFocus?.isConnected === true &&
      previousFocus.id === `archive-plant-${props.plant.id}`
        ? previousFocus
        : document.getElementById("garden-toggle");
    if (target instanceof HTMLElement) target.focus();
  });

  return (
    <div class="archive-confirmation-layer">
      <div class="archive-confirmation-layer__scrim" aria-hidden="true" />
      <section
        ref={(element) => {
          warning = element;
        }}
        class="archive-confirmation"
        role="alertdialog"
        aria-modal="true"
        aria-label={`Move ${name()} to cemetery`}
        aria-describedby="archive-consequence"
        tabIndex={-1}
      >
        <span class="archive-confirmation__warning" aria-hidden="true">
          !
        </span>
        <h2>Move {name()} to cemetery?</h2>
        <p id="archive-consequence">
          This moves {name()} to the cemetery permanently. It cannot be undone.
        </p>
        <Show when={error()}>
          {(message) => (
            <p class="inline-alert" role="alert">
              {message()}
            </p>
          )}
        </Show>
        <div class="archive-confirmation__actions">
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
            class="archive-confirmation__commit"
            onClick={() => void confirm()}
          >
            Move to cemetery
          </button>
        </div>
      </section>
    </div>
  );
};
