import { render, screen, within } from "@solidjs/testing-library";
import { describe, expect, it } from "vitest";
import {
  PhotoRemoveConfirmation,
  removePhotoControlId,
} from "../../src/app/PhotoRemoveConfirmation";
import * as Journal from "../../src/domain/Journal";

const photo: Journal.PlantPhoto = {
  id: Journal.photoId("ph1"),
  capturedAt: Journal.instant("2026-05-15T10:00:00Z"),
};

describe("photo remove confirmation", () => {
  it("should label the dialog and buttons for removing the photo", () => {
    render(() => (
      <PhotoRemoveConfirmation
        photo={photo}
        completed={false}
        onConfirm={() => Promise.resolve(undefined)}
        onCancel={() => undefined}
      />
    ));
    const dialog = screen.getByRole("alertdialog", { name: /Remove photo/ });
    expect(within(dialog).getByRole("button", { name: "Remove permanently" })).toBeInTheDocument();
    expect(within(dialog).getByText(/permanently removes the photo/)).toBeInTheDocument();
  });

  it("should restore focus to the photo's remove control on cancel", () => {
    const opener = document.createElement("button");
    opener.id = removePhotoControlId(photo.id);
    document.body.appendChild(opener);
    opener.focus();

    const { unmount } = render(() => (
      <PhotoRemoveConfirmation
        photo={photo}
        completed={false}
        onConfirm={() => Promise.resolve(undefined)}
        onCancel={() => undefined}
      />
    ));
    unmount();

    expect(opener).toHaveFocus();
    opener.remove();
  });

  it("should fall back to the sheet's close control when completed", () => {
    const closeControl = document.createElement("button");
    closeControl.id = "photos-sheet-close";
    document.body.append(closeControl);

    const { unmount } = render(() => (
      <PhotoRemoveConfirmation
        photo={photo}
        completed={true}
        onConfirm={() => Promise.resolve(undefined)}
        onCancel={() => undefined}
      />
    ));
    unmount();

    expect(closeControl).toHaveFocus();
    closeControl.remove();
  });
});
