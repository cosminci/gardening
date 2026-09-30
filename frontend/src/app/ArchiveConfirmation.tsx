import type { Component } from "solid-js";
import type * as Journal from "../domain/Journal";
import { ConfirmationDialog } from "./ConfirmationDialog";
import { plantDisplayName } from "./JournalLabels";
import { archivePlantControlId } from "./OperationControlIds";

interface ArchiveConfirmationProps {
  readonly plant: Journal.Plant;
  readonly completed: boolean;
  readonly onConfirm: () => Promise<string | undefined>;
  readonly onCancel: () => void;
}

export const ArchiveConfirmation: Component<ArchiveConfirmationProps> = (props) => {
  const name = () => plantDisplayName(props.plant);
  return (
    <ConfirmationDialog
      ariaLabel={`Move ${name()} to cemetery`}
      consequenceId="archive-consequence"
      title={<>Move {name()} to cemetery?</>}
      consequence={<>This moves {name()} to the cemetery permanently. It cannot be undone.</>}
      confirmLabel="Move to cemetery"
      completed={props.completed}
      returnFocus={{
        kind: "restoreOrFallback",
        expectedId: archivePlantControlId(props.plant.id),
        fallbackId: "garden-toggle",
      }}
      onConfirm={props.onConfirm}
      onCancel={props.onCancel}
    />
  );
};
