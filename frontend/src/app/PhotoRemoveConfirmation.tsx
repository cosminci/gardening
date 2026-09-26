import type { Component } from "solid-js";
import type * as Journal from "../domain/Journal";
import { ConfirmationDialog } from "./ConfirmationDialog";
import { formatLocalDateTime } from "./JournalLabels";

interface PhotoRemoveConfirmationProps {
  readonly photo: Journal.PlantPhoto;
  readonly completed: boolean;
  readonly onConfirm: () => Promise<string | undefined>;
  readonly onCancel: () => void;
}

export const removePhotoControlId = (id: Journal.PhotoId) => `remove-photo-${String(id)}`;

export const PhotoRemoveConfirmation: Component<PhotoRemoveConfirmationProps> = (props) => (
  <ConfirmationDialog
    ariaLabel={`Remove photo from ${formatLocalDateTime(props.photo.capturedAt)}`}
    consequenceId="remove-photo-consequence"
    title={<>Remove photo?</>}
    consequence={<>This permanently removes the photo. Removal cannot be undone.</>}
    confirmLabel="Remove permanently"
    completed={props.completed}
    returnFocus={{
      kind: "restoreOrFallback",
      expectedId: removePhotoControlId(props.photo.id),
      fallbackId: "photos-sheet-close",
    }}
    onConfirm={props.onConfirm}
    onCancel={props.onCancel}
  />
);
