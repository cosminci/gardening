import { For, Match, Show, Switch, createSignal, onCleanup, onMount } from "solid-js";
import type { Component } from "solid-js";
import type * as Journal from "../domain/Journal";
import type { PlantPhotoClient } from "../domain/PlantPhoto";
import { useBackgroundBarrier } from "./BackgroundBarrier";
import { formatLocalDateTime, plantDisplayName } from "./JournalLabels";
import { PhotoRemoveConfirmation, removePhotoControlId } from "./PhotoRemoveConfirmation";
import { photoContentUrl } from "../adapters/http/HttpPlantPhotoClient";
import "./sheet.css";
import "./plant-photos-sheet.css";

const photosPageSize = 6;
const acceptedMimeTypes = ["image/jpeg", "image/png"] as const;

interface PlantPhotosSheetProps {
  readonly plant: Journal.Plant;
  readonly photos: PlantPhotoClient;
  readonly onCancel: () => void;
}

type PhotosState =
  | { readonly kind: "idle" }
  | { readonly kind: "loading" }
  | { readonly kind: "failed" }
  | { readonly kind: "loaded"; readonly page: Journal.PhotoPage };

export const PlantPhotosSheet: Component<PlantPhotosSheetProps> = (props) => {
  let dialog!: HTMLElement;
  let pageStatus: HTMLSpanElement | undefined;
  let failureStatus: HTMLParagraphElement | undefined;

  const [state, setState] = createSignal<PhotosState>({ kind: "idle" });
  const [pageNumber, setPageNumber] = createSignal(1);
  const [closing, setClosing] = createSignal(false);
  const [removeTarget, setRemoveTarget] = createSignal<Journal.PlantPhoto>();
  const [removeCompleted, setRemoveCompleted] = createSignal(false);
  const [fullsizePhoto, setFullsizePhoto] = createSignal<Journal.PlantPhoto>();
  const [uploadError, setUploadError] = createSignal<string>();
  const [uploading, setUploading] = createSignal(false);

  let requestedPage = 1;

  useBackgroundBarrier();

  const waitForSheetTransition = () =>
    new Promise<void>((resolve) => {
      window.setTimeout(resolve, 180);
    });

  const closeSheet = async () => {
    if (closing()) return;
    setClosing(true);
    await waitForSheetTransition();
    props.onCancel();
  };

  const loadPage = async (page: number, focusResult = false) => {
    requestedPage = page;
    setState({ kind: "loading" });
    const outcome = await props.photos
      .getPhotos(props.plant.id, {
        offset: (page - 1) * photosPageSize,
        size: photosPageSize,
      })
      .then(
        (result) => ({ kind: "completed", result }) as const,
        () => ({ kind: "rejected" }) as const,
      );
    if (outcome.kind === "completed" && outcome.result.kind === "read") {
      setPageNumber(page);
      setState({ kind: "loaded", page: outcome.result.page });
      if (focusResult) queueMicrotask(() => pageStatus?.focus());
    } else {
      setState({ kind: "failed" });
      if (focusResult) queueMicrotask(() => failureStatus?.focus());
    }
  };

  const handleFileChange = async (event: Event) => {
    const input = event.currentTarget as HTMLInputElement;
    const file = input.files?.[0];
    if (file === undefined) return;

    setUploadError(undefined);

    if (!acceptedMimeTypes.includes(file.type as (typeof acceptedMimeTypes)[number])) {
      setUploadError("Unsupported file type. Choose a JPEG or PNG image.");
      input.value = "";
      return;
    }

    setUploading(true);
    const result = await props.photos.addPhoto(props.plant.id, file);
    setUploading(false);
    input.value = "";

    if (result.kind === "added") {
      await loadPage(1);
    } else if (result.kind === "plantMissing") {
      setUploadError("This plant no longer exists.");
    } else if (result.kind === "unsupportedMediaType") {
      setUploadError("Unsupported file type.");
    } else if (result.kind === "tooLarge") {
      setUploadError("The photo is too large to upload.");
    } else {
      setUploadError("The photo could not be uploaded.");
    }
  };

  const confirmRemove = async (photo: Journal.PlantPhoto): Promise<string | undefined> => {
    const result = await props.photos.removePhoto(photo.id);
    if (result.kind === "removed") {
      setRemoveCompleted(true);
      setRemoveTarget(undefined);
      const current = pageNumber();
      await loadPage(current);
      if (current > 1 && loadedPage()?.photos.length === 0) await loadPage(current - 1);
      return undefined;
    }
    if (result.kind === "photoMissing") return "This photo no longer exists.";
    return "The photo could not be removed.";
  };

  const openFullsize = (photo: Journal.PlantPhoto) => {
    setFullsizePhoto(photo);
  };

  const closeOverlay = () => {
    setFullsizePhoto(undefined);
  };

  const onKeyDown = (event: KeyboardEvent) => {
    if (fullsizePhoto() !== undefined) return;
    if (removeTarget() !== undefined) return;
    if (event.key === "Escape" && !uploading()) {
      event.preventDefault();
      void closeSheet();
    }
  };

  onMount(() => {
    window.addEventListener("keydown", onKeyDown);
    dialog.focus();
    void loadPage(1);
  });

  onCleanup(() => {
    window.removeEventListener("keydown", onKeyDown);
    document.getElementById(`plant-photos-${String(props.plant.id)}`)?.focus();
  });

  const loadedPage = () => {
    const current = state();
    return current.kind === "loaded" ? current.page : undefined;
  };

  return (
    <div class="sheet-layer">
      <div class="sheet-layer__scrim" aria-hidden="true" />
      <aside
        ref={(element) => {
          dialog = element;
        }}
        class="sheet sheet--photos sheet--entering"
        classList={{ "sheet--closing": closing() }}
        role="dialog"
        aria-label={`Photos for ${plantDisplayName(props.plant)}`}
        aria-modal={
          fullsizePhoto() === undefined && removeTarget() === undefined ? "true" : undefined
        }
        tabIndex="-1"
        inert={closing() || fullsizePhoto() !== undefined}
      >
        <header class="photos-sheet__header">
          <label class="photos-sheet__add-label" aria-label="Add photo" title="Add photo">
            <span aria-hidden="true">+</span>
            <input
              class="photos-sheet__add-input"
              type="file"
              accept="image/jpeg,image/png"
              aria-label="Choose a photo to upload"
              disabled={uploading()}
              onChange={(event) => void handleFileChange(event)}
            />
          </label>
          <button
            id="photos-sheet-close"
            class="icon-action sheet-collapse"
            type="button"
            aria-label={`Close photos for ${plantDisplayName(props.plant)}`}
            disabled={uploading()}
            onClick={() => void closeSheet()}
          >
            <span class="sheet-collapse__icon" aria-hidden="true" />
          </button>
        </header>
        <div class="photos-sheet__body">
          <div class="photos-sheet__status">
            <Show when={uploading()}>
              <span aria-live="polite">Uploading…</span>
            </Show>
            <Show when={uploadError()}>
              {(message) => (
                <p class="photos-sheet__upload-error" role="alert">
                  {message()}
                </p>
              )}
            </Show>
          </div>
          <Switch>
            <Match when={state().kind === "loading"}>
              <p class="photos-sheet__state">Loading photos…</p>
            </Match>
            <Match when={state().kind === "failed"}>
              <p
                ref={(element) => {
                  failureStatus = element;
                }}
                class="photos-sheet__state"
                role="alert"
                tabindex="-1"
              >
                Photos could not be loaded.
              </p>
              <button
                class="compact-action"
                type="button"
                onClick={() => void loadPage(requestedPage, true)}
              >
                Retry
              </button>
            </Match>
            <Match when={loadedPage()} keyed>
              {(page) => (
                <div>
                  <Show
                    when={page.photos.length > 0}
                    fallback={<p class="photos-sheet__state">No photos yet.</p>}
                  >
                    <div class="photos-grid">
                      <For each={page.photos}>
                        {(photo) => (
                          <div class="photo-thumb">
                            <div class="photo-thumb__img-wrap">
                              <img
                                class="photo-thumb__img"
                                src={photoContentUrl(photo.id, "thumbnail")}
                                alt={`Photo from ${formatLocalDateTime(photo.capturedAt)}`}
                                onClick={() => {
                                  openFullsize(photo);
                                }}
                              />
                              <button
                                id={removePhotoControlId(photo.id)}
                                class="photo-thumb__remove"
                                type="button"
                                aria-label={`Remove photo from ${formatLocalDateTime(photo.capturedAt)}`}
                                onClick={() => {
                                  setRemoveCompleted(false);
                                  setRemoveTarget(photo);
                                }}
                              >
                                ×
                              </button>
                            </div>
                            <p class="photo-thumb__caption">
                              {formatLocalDateTime(photo.capturedAt)}
                            </p>
                          </div>
                        )}
                      </For>
                    </div>
                  </Show>
                  <nav class="photos-sheet__pagination" aria-label="Photo pages">
                    <button
                      class="compact-action"
                      type="button"
                      disabled={pageNumber() === 1}
                      onClick={() => void loadPage(pageNumber() - 1, true)}
                    >
                      Previous
                    </button>
                    <span
                      ref={(element) => {
                        pageStatus = element;
                      }}
                      tabindex="-1"
                      aria-live="polite"
                    >
                      Page {pageNumber()}
                    </span>
                    <button
                      class="compact-action"
                      type="button"
                      disabled={!page.hasNextPage}
                      onClick={() => void loadPage(pageNumber() + 1, true)}
                    >
                      Next
                    </button>
                  </nav>
                </div>
              )}
            </Match>
          </Switch>
        </div>
      </aside>
      <Show when={fullsizePhoto()} keyed>
        {(photo) => <PhotoOverlay photo={photo} onClose={closeOverlay} />}
      </Show>
      <Show when={removeTarget()} keyed>
        {(photo) => (
          <PhotoRemoveConfirmation
            photo={photo}
            completed={removeCompleted()}
            onConfirm={() => confirmRemove(photo)}
            onCancel={() => {
              setRemoveTarget(undefined);
            }}
          />
        )}
      </Show>
    </div>
  );
};

const PhotoOverlay: Component<{
  photo: Journal.PlantPhoto;
  onClose: () => void;
}> = (props) => {
  let closeButton!: HTMLButtonElement;
  const previousFocus = document.activeElement;

  const onKeyDown = (event: KeyboardEvent) => {
    if (event.key === "Escape") {
      event.preventDefault();
      event.stopImmediatePropagation();
      props.onClose();
    } else if (event.key === "Tab") {
      event.preventDefault();
      event.stopImmediatePropagation();
      closeButton.focus();
    }
  };

  onMount(() => {
    window.addEventListener("keydown", onKeyDown);
    closeButton.focus();
  });

  onCleanup(() => {
    window.removeEventListener("keydown", onKeyDown);
    // document.activeElement is always an HTMLElement (defaults to <body>) in this app, which never
    // focuses SVG or other non-HTML elements; the false side is an unreachable domain invariant.
    /* v8 ignore next */
    if (previousFocus instanceof HTMLElement) previousFocus.focus();
  });

  return (
    <div class="photo-overlay-layer" role="dialog" aria-modal="true" aria-label="Photo viewer">
      <div
        class="photo-overlay-layer__backdrop"
        aria-hidden="true"
        onClick={() => {
          props.onClose();
        }}
      />
      <div class="photo-overlay__content">
        <button
          ref={(element) => {
            closeButton = element;
          }}
          class="icon-action photo-overlay__close"
          type="button"
          aria-label="Close photo viewer"
          onClick={() => {
            props.onClose();
          }}
        >
          ×
        </button>
        <img
          class="photo-overlay__image"
          src={photoContentUrl(props.photo.id, "original")}
          alt=""
        />
      </div>
    </div>
  );
};
