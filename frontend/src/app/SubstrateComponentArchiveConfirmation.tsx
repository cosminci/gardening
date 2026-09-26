import type { Component } from "solid-js";
import type * as Journal from "../domain/Journal";
import { ConfirmationDialog } from "./ConfirmationDialog";
import { archiveSubstrateComponentControlId } from "./OperationControlIds";

interface SubstrateComponentArchiveConfirmationProps {
  readonly component: Journal.SubstrateComponent;
  readonly completed: boolean;
  readonly onConfirm: () => Promise<string | undefined>;
  readonly onCancel: () => void;
}

export const SubstrateComponentArchiveConfirmation: Component<
  SubstrateComponentArchiveConfirmationProps
> = (props) => (
  <ConfirmationDialog
    ariaLabel={`Archive ${props.component.data.name}`}
    consequenceId="substrate-component-archive-consequence"
    title={<>Archive {props.component.data.name}?</>}
    consequence={
      <>
        This substrate component will no longer be offered for new plants or repots. Plants and
        operations that already reference it keep showing it. Archiving cannot be undone.
      </>
    }
    confirmLabel="Archive permanently"
    completed={props.completed}
    returnFocus={{
      kind: "restoreOnCancelOnly",
      returnFocusId: archiveSubstrateComponentControlId(props.component.id),
    }}
    onConfirm={props.onConfirm}
    onCancel={props.onCancel}
  />
);
