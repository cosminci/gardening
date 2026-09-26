import { fireEvent, render, screen, waitFor, within } from "@solidjs/testing-library";
import { describe, expect, it, vi } from "vitest";
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
  it("should keep keyboard focus inside the permanent-remove warning", () => {
    const onCancel = vi.fn();
    render(() => (
      <PhotoRemoveConfirmation
        photo={photo}
        completed={false}
        onConfirm={() => Promise.resolve(undefined)}
        onCancel={onCancel}
      />
    ));
    const warning = screen.getByRole("alertdialog", { name: /Remove photo/ });
    const cancel = within(warning).getByRole("button", { name: "Cancel" });
    const confirm = within(warning).getByRole("button", { name: "Remove permanently" });

    const initialFocus = document.activeElement;
    fireEvent.keyDown(window, { key: "Tab", shiftKey: true });
    const backwardsFocus = document.activeElement;
    fireEvent.keyDown(window, { key: "Tab" });
    const forwardsFocus = document.activeElement;
    fireEvent.keyDown(window, { key: "Tab" });
    const forwardsFromCancelFocus = document.activeElement;
    fireEvent.keyDown(window, { key: "Escape" });
    const escapeCalls = onCancel.mock.calls.length;
    fireEvent.click(cancel);

    expect(initialFocus).toBe(cancel);
    expect(backwardsFocus).toBe(confirm);
    expect(forwardsFocus).toBe(cancel);
    expect(forwardsFromCancelFocus).toBe(cancel);
    expect(escapeCalls).toBe(1);
    expect(onCancel).toHaveBeenCalledTimes(2);
  });

  it("should prevent duplicate removal while the request is pending and show failures", async () => {
    let finish: (message: string) => void = () => undefined;
    const onConfirm = vi.fn(
      () =>
        new Promise<string>((resolve) => {
          finish = resolve;
        }),
    );
    const onCancel = vi.fn();
    render(() => (
      <PhotoRemoveConfirmation
        photo={photo}
        completed={false}
        onConfirm={onConfirm}
        onCancel={onCancel}
      />
    ));

    const warning = screen.getByRole("alertdialog", { name: /Remove photo/ });
    const cancel = within(warning).getByRole("button", { name: "Cancel" });
    const confirm = within(warning).getByRole("button", { name: "Remove permanently" });

    fireEvent.click(confirm);
    const pendingFocus = document.activeElement;
    fireEvent.keyDown(window, { key: "Tab" });
    const trappedFocus = document.activeElement;
    fireEvent.keyDown(window, { key: "Escape" });
    const pendingCancelCalls = onCancel.mock.calls.length;
    finish("The photo could not be removed.");

    const alert = await screen.findByRole("alert");
    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Remove permanently" })).toBeEnabled();
    });
    const restoredFocus = document.activeElement;
    fireEvent.keyDown(window, { key: "Escape" });

    expect(pendingFocus).toBe(warning);
    expect(trappedFocus).toBe(warning);
    expect(pendingCancelCalls).toBe(0);
    expect(alert).toHaveTextContent("The photo could not be removed.");
    expect(restoredFocus).toBe(cancel);
    expect(onCancel).toHaveBeenCalledOnce();
    expect(onConfirm).toHaveBeenCalledOnce();
  });

  it("should inert the background and restore focus to the opening control on cancel", () => {
    const masthead = document.createElement("div");
    masthead.className = "masthead";
    const journalRegion = document.createElement("div");
    journalRegion.className = "journal";
    const opener = document.createElement("button");
    opener.id = removePhotoControlId(photo.id);
    document.body.append(masthead, journalRegion, opener);
    opener.focus();

    const { unmount } = render(() => (
      <PhotoRemoveConfirmation
        photo={photo}
        completed={false}
        onConfirm={() => Promise.resolve(undefined)}
        onCancel={() => undefined}
      />
    ));

    expect(masthead.inert).toBe(true);
    expect(journalRegion.inert).toBe(true);

    unmount();

    expect(masthead.inert).toBe(false);
    expect(journalRegion.inert).toBe(false);
    expect(opener).toHaveFocus();

    masthead.remove();
    journalRegion.remove();
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
