import type { Component } from "solid-js";
import type * as Journal from "../domain/Journal";
import { ConfirmationDialog } from "./ConfirmationDialog";
import { archivePesticideControlId } from "./OperationControlIds";

interface PesticideArchiveConfirmationProps {
  readonly pesticide: Journal.Pesticide;
  readonly completed: boolean;
  readonly onConfirm: () => Promise<string | undefined>;
  readonly onCancel: () => void;
}

export const PesticideArchiveConfirmation: Component<PesticideArchiveConfirmationProps> = (
  props,
) => (
  <ConfirmationDialog
    ariaLabel={`Archive ${props.pesticide.data.name}`}
    consequenceId="pesticide-archive-consequence"
    title={<>Archive {props.pesticide.data.name}?</>}
    consequence={
      <>
        This pesticide will no longer be offered for new operations. Operations that already
        reference it keep showing it. Archiving cannot be undone.
      </>
    }
    confirmLabel="Archive permanently"
    completed={props.completed}
    returnFocus={{
      kind: "restoreOnCancelOnly",
      returnFocusId: archivePesticideControlId(props.pesticide.id),
    }}
    onConfirm={props.onConfirm}
    onCancel={props.onCancel}
  />
);
