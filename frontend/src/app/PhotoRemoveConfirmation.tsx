import { Show, createSignal, onCleanup, onMount } from "solid-js";
import type { Component } from "solid-js";
import type * as Journal from "../domain/Journal";
import { formatLocalDateTime } from "./JournalLabels";
import "./photo-remove-confirmation.css";

interface PhotoRemoveConfirmationProps {
  readonly photo: Journal.PlantPhoto;
  readonly completed: boolean;
  readonly onConfirm: () => Promise<string | undefined>;
  readonly onCancel: () => void;
}

export const removePhotoControlId = (photoId: Journal.PhotoId) => `remove-photo-${String(photoId)}`;

export const PhotoRemoveConfirmation: Component<PhotoRemoveConfirmationProps> = (props) => {
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
      previousFocus.id === removePhotoControlId(props.photo.id)
        ? previousFocus
        : document.getElementById("photos-sheet-close");
    if (target instanceof HTMLElement) target.focus();
  });

  return (
    <div class="photo-remove-confirmation-layer">
      <div class="photo-remove-confirmation-layer__scrim" aria-hidden="true" />
      <section
        ref={(element) => {
          warning = element;
        }}
        class="photo-remove-confirmation"
        role="alertdialog"
        aria-modal="true"
        aria-label={`Remove photo from ${formatLocalDateTime(props.photo.capturedAt)}`}
        aria-describedby="remove-photo-consequence"
        tabIndex={-1}
      >
        <span class="photo-remove-confirmation__warning" aria-hidden="true">
          !
        </span>
        <h2>Remove photo?</h2>
        <p id="remove-photo-consequence">
          This permanently removes the photo. Removal cannot be undone.
        </p>
        <Show when={error()}>
          {(message) => (
            <p class="inline-alert" role="alert">
              {message()}
            </p>
          )}
        </Show>
        <div class="photo-remove-confirmation__actions">
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
            class="photo-remove-confirmation__commit"
            onClick={() => void confirm()}
          >
            Remove permanently
          </button>
        </div>
      </section>
    </div>
  );
};
