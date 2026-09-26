import type { Component } from "solid-js";
import type * as Journal from "../domain/Journal";
import { ConfirmationDialog } from "./ConfirmationDialog";
import * as Labels from "./JournalLabels";
import { deleteOperationControlId } from "./OperationControlIds";

interface DeleteOperationConfirmationProps {
  readonly operation: Journal.Operation;
  readonly completed: boolean;
  readonly onConfirm: () => Promise<string | undefined>;
  readonly onCancel: () => void;
}

export const DeleteOperationConfirmation: Component<DeleteOperationConfirmationProps> = (props) => {
  const label = () =>
    `Delete this ${Labels.operationKindLabel(props.operation.details)} operation from ${Labels.formatLocalDate(props.operation.date)}`;
  return (
    <ConfirmationDialog
      ariaLabel={label()}
      consequenceId="delete-operation-consequence"
      title={<>{label()}?</>}
      consequence={
        <>
          This removes the operation from the plant's history permanently. Deleting cannot be
          undone.
        </>
      }
      confirmLabel="Delete permanently"
      completed={props.completed}
      returnFocus={{
        kind: "restoreOnCancelOnly",
        returnFocusId: deleteOperationControlId(props.operation.id),
      }}
      onConfirm={props.onConfirm}
      onCancel={props.onCancel}
    />
  );
};
