import type { Component } from "solid-js";
import type * as Journal from "../domain/Journal";
import { ConfirmationDialog } from "./ConfirmationDialog";
import { deleteSubstrateMixControlId } from "./OperationControlIds";

interface DeleteSubstrateMixConfirmationProps {
  readonly mix: Journal.SubstrateMix;
  readonly completed: boolean;
  readonly onConfirm: () => Promise<string | undefined>;
  readonly onCancel: () => void;
}

export const DeleteSubstrateMixConfirmation: Component<DeleteSubstrateMixConfirmationProps> = (
  props,
) => (
  <ConfirmationDialog
    ariaLabel={`Delete ${props.mix.name}`}
    consequenceId="delete-substrate-mix-consequence"
    title={<>Delete {props.mix.name}?</>}
    consequence={<>This removes the saved mix permanently. Deleting cannot be undone.</>}
    confirmLabel="Delete permanently"
    completed={props.completed}
    returnFocus={{
      kind: "restoreOrFallback",
      expectedId: deleteSubstrateMixControlId(props.mix.id),
      fallbackId: "load-substrate-mix-sheet",
    }}
    onConfirm={props.onConfirm}
    onCancel={props.onCancel}
  />
);
