import { render, screen, waitFor, within } from "@solidjs/testing-library";
import { describe, expect, it } from "vitest";
import { ArchiveConfirmation } from "../../src/app/ArchiveConfirmation";
import { archivePlantControlId } from "../../src/app/OperationControlIds";
import { ficus } from "./JournalTestSupport";

describe("archive confirmation", () => {
  it("should label the dialog and buttons for moving the named plant to the cemetery", () => {
    render(() => (
      <ArchiveConfirmation
        plant={ficus()}
        completed={false}
        onConfirm={() => Promise.resolve(undefined)}
        onCancel={() => undefined}
      />
    ));
    const dialog = screen.getByRole("alertdialog", { name: "Move Fern to cemetery" });
    expect(within(dialog).getByRole("button", { name: "Move to cemetery" })).toBeInTheDocument();
    expect(within(dialog).getByText(/moves Fern to the cemetery permanently/)).toBeInTheDocument();
  });

  it("should fall back to the garden toggle when the archived plant's control is gone", async () => {
    const gardenToggle = document.createElement("button");
    gardenToggle.id = "garden-toggle";
    document.body.appendChild(gardenToggle);
    const plant = ficus();
    const opener = document.createElement("button");
    opener.id = archivePlantControlId(plant.id);
    document.body.appendChild(opener);
    opener.focus();

    const { unmount } = render(() => (
      <ArchiveConfirmation
        plant={plant}
        completed={true}
        onConfirm={() => Promise.resolve(undefined)}
        onCancel={() => undefined}
      />
    ));
    unmount();

    await waitFor(() => {
      expect(gardenToggle).toHaveFocus();
    });
    gardenToggle.remove();
    opener.remove();
  });
});
