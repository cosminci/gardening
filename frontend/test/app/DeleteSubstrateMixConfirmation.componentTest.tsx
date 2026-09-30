import { render, screen, waitFor, within } from "@solidjs/testing-library";
import { describe, expect, it } from "vitest";
import { DeleteSubstrateMixConfirmation } from "../../src/app/DeleteSubstrateMixConfirmation";
import { deleteSubstrateMixControlId } from "../../src/app/OperationControlIds";
import * as Journal from "../../src/domain/Journal";

const mix: Journal.SubstrateMix = {
  id: Journal.substrateMixId("00000000-0000-4000-8000-000000000009"),
  name: Journal.substrateMixName("Standard mix"),
  maybeNotes: null,
  substrate: Journal.substrate([
    {
      component: Journal.substrateComponentId("00000000-0000-4000-8000-000000000003"),
      share: Journal.percentage(100),
    },
  ]),
};

describe("delete substrate mix confirmation", () => {
  it("should label the dialog and buttons for deleting the named mix", () => {
    render(() => (
      <DeleteSubstrateMixConfirmation
        mix={mix}
        completed={false}
        onConfirm={() => Promise.resolve(undefined)}
        onCancel={() => undefined}
      />
    ));
    const dialog = screen.getByRole("alertdialog", { name: `Delete ${mix.name}` });
    expect(within(dialog).getByRole("button", { name: "Delete permanently" })).toBeInTheDocument();
    expect(within(dialog).getByText(/removes the saved mix permanently/)).toBeInTheDocument();
  });

  it("should restore focus to the mix's own delete control on cancel", async () => {
    const opener = document.createElement("button");
    opener.id = deleteSubstrateMixControlId(mix.id);
    document.body.appendChild(opener);
    opener.focus();

    const cancelled = render(() => (
      <DeleteSubstrateMixConfirmation
        mix={mix}
        completed={false}
        onConfirm={() => Promise.resolve(undefined)}
        onCancel={() => undefined}
      />
    ));
    cancelled.unmount();
    await waitFor(() => {
      expect(opener).toHaveFocus();
    });
    opener.remove();
  });
});
