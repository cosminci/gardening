import { fireEvent, render, screen, waitFor, within } from "@solidjs/testing-library";
import { describe, expect, it, vi } from "vitest";
import { ConfirmationDialog } from "../../src/app/ConfirmationDialog";

describe("confirmation dialog", () => {
  it("should focus cancel on mount, trap Tab between the two buttons, and cancel on Escape when idle", () => {
    const onCancel = vi.fn();
    render(() => (
      <ConfirmationDialog
        ariaLabel="Confirm test action"
        consequenceId="test-consequence"
        title={<>Test action?</>}
        consequence={<>This action cannot be undone.</>}
        confirmLabel="Confirm"
        completed={false}
        returnFocus={{ kind: "restoreOnCancelOnly", returnFocusId: "test-opener" }}
        onConfirm={() => Promise.resolve(undefined)}
        onCancel={onCancel}
      />
    ));
    const dialog = screen.getByRole("alertdialog", { name: "Confirm test action" });
    const cancel = within(dialog).getByRole("button", { name: "Cancel" });
    const confirm = within(dialog).getByRole("button", { name: "Confirm" });

    expect(document.activeElement).toBe(cancel);
    // Shift+Tab from cancel wraps to confirm
    fireEvent.keyDown(window, { key: "Tab", shiftKey: true });
    expect(document.activeElement).toBe(confirm);
    // Tab from confirm wraps to cancel
    fireEvent.keyDown(window, { key: "Tab" });
    expect(document.activeElement).toBe(cancel);
    // Tab from cancel: exercises the else-if-false branch (no movement)
    fireEvent.keyDown(window, { key: "Tab" });
    // Escape calls onCancel
    fireEvent.keyDown(window, { key: "Escape" });
    expect(onCancel).toHaveBeenCalledOnce();
  });

  it("should make .masthead and .journal inert on mount and release them on unmount", () => {
    const masthead = document.createElement("header");
    masthead.className = "masthead";
    const journal = document.createElement("section");
    journal.className = "journal";
    document.body.append(masthead, journal);

    const { unmount } = render(() => (
      <ConfirmationDialog
        ariaLabel="Confirm test action"
        consequenceId="test-consequence"
        title={<>Test action?</>}
        consequence={<>This action cannot be undone.</>}
        confirmLabel="Confirm"
        completed={false}
        returnFocus={{ kind: "restoreOnCancelOnly", returnFocusId: "test-opener" }}
        onConfirm={() => Promise.resolve(undefined)}
        onCancel={() => undefined}
      />
    ));

    expect(masthead.inert).toBe(true);
    expect(journal.inert).toBe(true);
    unmount();
    expect(masthead.inert).toBe(false);
    expect(journal.inert).toBe(false);

    masthead.remove();
    journal.remove();
  });

  it("should block duplicate confirms, trap focus to the warning while pending, then show the error and restore cancel focus", async () => {
    let finish: (message: string) => void = () => undefined;
    const onConfirm = vi.fn(
      () =>
        new Promise<string>((resolve) => {
          finish = resolve;
        }),
    );
    const onCancel = vi.fn();
    render(() => (
      <ConfirmationDialog
        ariaLabel="Confirm test action"
        consequenceId="test-consequence"
        title={<>Test action?</>}
        consequence={<>This action cannot be undone.</>}
        confirmLabel="Confirm"
        completed={false}
        returnFocus={{ kind: "restoreOnCancelOnly", returnFocusId: "test-opener" }}
        onConfirm={onConfirm}
        onCancel={onCancel}
      />
    ));
    const dialog = screen.getByRole("alertdialog", { name: "Confirm test action" });
    const cancel = within(dialog).getByRole("button", { name: "Cancel" });
    const confirm = within(dialog).getByRole("button", { name: "Confirm" });

    fireEvent.click(confirm);
    // Pending: focus traps to the warning section
    expect(document.activeElement).toBe(dialog);
    fireEvent.keyDown(window, { key: "Tab" });
    expect(document.activeElement).toBe(dialog);
    // Escape while pending does not cancel
    fireEvent.keyDown(window, { key: "Escape" });
    expect(onCancel).not.toHaveBeenCalled();
    // onConfirm called exactly once (duplicate click impossible — button disabled)
    expect(onConfirm).toHaveBeenCalledOnce();

    finish("Something went wrong.");

    const alert = await screen.findByRole("alert");
    await waitFor(() => {
      expect(confirm).toBeEnabled();
    });
    expect(alert).toHaveTextContent("Something went wrong.");
    // Focus restored to cancel after error
    expect(document.activeElement).toBe(cancel);
    // Escape after error does cancel
    fireEvent.keyDown(window, { key: "Escape" });
    expect(onCancel).toHaveBeenCalledOnce();
  });

  it("should restore focus to a named opener on cancel and skip it once completed (restoreOnCancelOnly)", async () => {
    const opener = document.createElement("button");
    opener.id = "test-opener";
    document.body.appendChild(opener);

    const cancelled = render(() => (
      <ConfirmationDialog
        ariaLabel="Confirm test action"
        consequenceId="test-consequence"
        title={<>Test action?</>}
        consequence={<>This action cannot be undone.</>}
        confirmLabel="Confirm"
        completed={false}
        returnFocus={{ kind: "restoreOnCancelOnly", returnFocusId: "test-opener" }}
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
      <ConfirmationDialog
        ariaLabel="Confirm test action"
        consequenceId="test-consequence"
        title={<>Test action?</>}
        consequence={<>This action cannot be undone.</>}
        confirmLabel="Confirm"
        completed={true}
        returnFocus={{ kind: "restoreOnCancelOnly", returnFocusId: "test-opener" }}
        onConfirm={() => Promise.resolve(undefined)}
        onCancel={() => undefined}
      />
    ));
    completed.unmount();

    expect(opener).not.toHaveFocus();
    opener.remove();
  });

  it("should restore focus to the matching opener on cancel (restoreOrFallback)", () => {
    const fallback = document.createElement("button");
    fallback.id = "test-fallback";
    document.body.appendChild(fallback);
    const opener = document.createElement("button");
    opener.id = "expected-opener";
    document.body.appendChild(opener);
    opener.focus();

    const { unmount } = render(() => (
      <ConfirmationDialog
        ariaLabel="Confirm test action"
        consequenceId="test-consequence"
        title={<>Test action?</>}
        consequence={<>This action cannot be undone.</>}
        confirmLabel="Confirm"
        completed={false}
        returnFocus={{
          kind: "restoreOrFallback",
          expectedId: "expected-opener",
          fallbackId: "test-fallback",
        }}
        onConfirm={() => Promise.resolve(undefined)}
        onCancel={() => undefined}
      />
    ));
    unmount();

    expect(opener).toHaveFocus();
    opener.remove();
    fallback.remove();
  });

  it("should fall back to the fallback element when completed (restoreOrFallback)", () => {
    const fallback = document.createElement("button");
    fallback.id = "test-fallback";
    document.body.appendChild(fallback);
    const opener = document.createElement("button");
    opener.id = "expected-opener";
    document.body.appendChild(opener);
    opener.focus();

    const { unmount } = render(() => (
      <ConfirmationDialog
        ariaLabel="Confirm test action"
        consequenceId="test-consequence"
        title={<>Test action?</>}
        consequence={<>This action cannot be undone.</>}
        confirmLabel="Confirm"
        completed={true}
        returnFocus={{
          kind: "restoreOrFallback",
          expectedId: "expected-opener",
          fallbackId: "test-fallback",
        }}
        onConfirm={() => Promise.resolve(undefined)}
        onCancel={() => undefined}
      />
    ));
    unmount();

    expect(fallback).toHaveFocus();
    opener.remove();
    fallback.remove();
  });

  it("should fall back when the opener id is absent from the DOM (restoreOrFallback)", () => {
    const fallback = document.createElement("button");
    fallback.id = "test-fallback";
    fallback.tabIndex = -1;
    document.body.appendChild(fallback);
    const other = document.createElement("button");
    other.id = "some-other-control";
    document.body.appendChild(other);
    other.focus();

    const { unmount } = render(() => (
      <ConfirmationDialog
        ariaLabel="Confirm test action"
        consequenceId="test-consequence"
        title={<>Test action?</>}
        consequence={<>This action cannot be undone.</>}
        confirmLabel="Confirm"
        completed={false}
        returnFocus={{
          kind: "restoreOrFallback",
          expectedId: "expected-opener",
          fallbackId: "test-fallback",
        }}
        onConfirm={() => Promise.resolve(undefined)}
        onCancel={() => undefined}
      />
    ));
    unmount();

    expect(fallback).toHaveFocus();
    other.remove();
    fallback.remove();
  });
});
