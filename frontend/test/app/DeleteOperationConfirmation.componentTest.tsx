import { render, screen, waitFor, within } from "@solidjs/testing-library";
import { describe, expect, it } from "vitest";
import { DeleteOperationConfirmation } from "../../src/app/DeleteOperationConfirmation";
import { repot } from "./JournalTestSupport";

describe("delete operation confirmation", () => {
  it("should label the dialog and buttons for deleting the named operation", () => {
    render(() => (
      <DeleteOperationConfirmation
        operation={repot("o1", "2026-03-03T00:00:00Z")}
        completed={false}
        onConfirm={() => Promise.resolve(undefined)}
        onCancel={() => undefined}
      />
    ));
    const dialog = screen.getByRole("alertdialog", {
      name: "Delete this Repot operation from 03.03.2026",
    });
    expect(within(dialog).getByRole("button", { name: "Delete permanently" })).toBeInTheDocument();
    expect(
      within(dialog).getByText(/removes the operation from the plant's history permanently/),
    ).toBeInTheDocument();
  });

  it("should restore focus to the operation's delete control on cancel, but not once completed", async () => {
    const opener = document.createElement("button");
    opener.id = "delete-operation-o1";
    document.body.appendChild(opener);
    const operation = repot("o1", "2026-03-03T00:00:00Z");

    const cancelled = render(() => (
      <DeleteOperationConfirmation
        operation={operation}
        completed={false}
        onConfirm={() => Promise.resolve(undefined)}
        onCancel={() => undefined}
      />
    ));
    cancelled.unmount();
    await waitFor(() => {
      expect(opener).toHaveFocus();
    });

    opener.blur();
    const completed = render(() => (
      <DeleteOperationConfirmation
        operation={operation}
        completed={true}
        onConfirm={() => Promise.resolve(undefined)}
        onCancel={() => undefined}
      />
    ));
    completed.unmount();

    expect(opener).not.toHaveFocus();
    opener.remove();
  });
});
