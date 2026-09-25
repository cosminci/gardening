import { fireEvent, render, screen, waitFor, within } from "@solidjs/testing-library";
import { describe, expect, it, vi } from "vitest";
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
  it("should keep keyboard focus inside the permanent-deletion warning", () => {
    const masthead = document.createElement("header");
    masthead.className = "masthead";
    const journal = document.createElement("section");
    journal.className = "journal";
    document.body.append(masthead, journal);

    const onCancel = vi.fn();
    const { unmount } = render(() => (
      <DeleteSubstrateMixConfirmation
        mix={mix}
        completed={false}
        onConfirm={() => Promise.resolve(undefined)}
        onCancel={onCancel}
      />
    ));
    const warning = screen.getByRole("alertdialog", { name: `Delete ${mix.name}` });
    const cancel = within(warning).getByRole("button", { name: "Cancel" });
    const confirm = within(warning).getByRole("button", { name: "Delete permanently" });

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
    expect(masthead.inert).toBe(true);
    expect(journal.inert).toBe(true);
    unmount();
    expect(masthead.inert).toBe(false);
    expect(journal.inert).toBe(false);
    masthead.remove();
    journal.remove();
  });

  it("should prevent duplicate deletion while the request is pending and show failures", async () => {
    let finish: (message: string) => void = () => undefined;
    const onConfirm = vi.fn(
      () =>
        new Promise<string>((resolve) => {
          finish = resolve;
        }),
    );
    const onCancel = vi.fn();
    render(() => (
      <DeleteSubstrateMixConfirmation
        mix={mix}
        completed={false}
        onConfirm={onConfirm}
        onCancel={onCancel}
      />
    ));

    const warning = screen.getByRole("alertdialog", { name: `Delete ${mix.name}` });
    const cancel = within(warning).getByRole("button", { name: "Cancel" });
    const confirm = within(warning).getByRole("button", { name: "Delete permanently" });

    fireEvent.click(confirm);
    const pendingFocus = document.activeElement;
    fireEvent.keyDown(window, { key: "Tab" });
    const trappedFocus = document.activeElement;
    fireEvent.keyDown(window, { key: "Escape" });
    const pendingCancelCalls = onCancel.mock.calls.length;
    finish("The mix could not be deleted.");

    const alert = await screen.findByRole("alert");
    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Delete permanently" })).toBeEnabled();
    });
    const restoredFocus = document.activeElement;
    fireEvent.keyDown(window, { key: "Escape" });

    expect(pendingFocus).toBe(warning);
    expect(trappedFocus).toBe(warning);
    expect(pendingCancelCalls).toBe(0);
    expect(alert).toHaveTextContent("The mix could not be deleted.");
    expect(restoredFocus).toBe(cancel);
    expect(onCancel).toHaveBeenCalledOnce();
    expect(onConfirm).toHaveBeenCalledOnce();
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

  it("should fall back to the load-mix sheet once completed, even if the opener still matches", async () => {
    const loadSheet = document.createElement("section");
    loadSheet.id = "load-substrate-mix-sheet";
    loadSheet.tabIndex = -1;
    document.body.appendChild(loadSheet);
    const opener = document.createElement("button");
    opener.id = deleteSubstrateMixControlId(mix.id);
    document.body.appendChild(opener);
    opener.focus();

    const completed = render(() => (
      <DeleteSubstrateMixConfirmation
        mix={mix}
        completed={true}
        onConfirm={() => Promise.resolve(undefined)}
        onCancel={() => undefined}
      />
    ));
    completed.unmount();

    await waitFor(() => {
      expect(loadSheet).toHaveFocus();
    });
    opener.remove();
    loadSheet.remove();
  });

  it("should fall back to the load-mix sheet when the opener no longer matches or is gone", async () => {
    const loadSheet = document.createElement("section");
    loadSheet.id = "load-substrate-mix-sheet";
    loadSheet.tabIndex = -1;
    document.body.appendChild(loadSheet);
    const other = document.createElement("button");
    other.id = "some-other-control";
    document.body.appendChild(other);
    other.focus();

    const mismatched = render(() => (
      <DeleteSubstrateMixConfirmation
        mix={mix}
        completed={false}
        onConfirm={() => Promise.resolve(undefined)}
        onCancel={() => undefined}
      />
    ));
    mismatched.unmount();
    await waitFor(() => {
      expect(loadSheet).toHaveFocus();
    });
    other.remove();
    loadSheet.remove();
  });
});
