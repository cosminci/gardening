import { fireEvent, render, screen, waitFor, within } from "@solidjs/testing-library";
import { describe, expect, it, vi } from "vitest";
import { DeleteOperationConfirmation } from "../../src/app/DeleteOperationConfirmation";
import { repot } from "./JournalTestSupport";

describe("delete operation confirmation", () => {
  it("should keep keyboard focus inside the permanent-deletion warning", () => {
    const onCancel = vi.fn();
    render(() => (
      <DeleteOperationConfirmation
        operation={repot("o1", "2026-03-03T00:00:00Z")}
        completed={false}
        onConfirm={() => Promise.resolve(undefined)}
        onCancel={onCancel}
      />
    ));
    const warning = screen.getByRole("alertdialog", {
      name: "Delete this Repot operation from 03.03.2026",
    });
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
      <DeleteOperationConfirmation
        operation={repot("o1", "2026-03-03T00:00:00Z")}
        completed={false}
        onConfirm={onConfirm}
        onCancel={onCancel}
      />
    ));

    const warning = screen.getByRole("alertdialog", {
      name: "Delete this Repot operation from 03.03.2026",
    });
    const cancel = within(warning).getByRole("button", { name: "Cancel" });
    const confirm = within(warning).getByRole("button", { name: "Delete permanently" });

    fireEvent.click(confirm);
    const pendingFocus = document.activeElement;
    fireEvent.keyDown(window, { key: "Tab" });
    const trappedFocus = document.activeElement;
    fireEvent.keyDown(window, { key: "Escape" });
    const pendingCancelCalls = onCancel.mock.calls.length;
    finish("The operation could not be deleted.");

    const alert = await screen.findByRole("alert");
    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Delete permanently" })).toBeEnabled();
    });
    const restoredFocus = document.activeElement;
    fireEvent.keyDown(window, { key: "Escape" });

    expect(pendingFocus).toBe(warning);
    expect(trappedFocus).toBe(warning);
    expect(pendingCancelCalls).toBe(0);
    expect(alert).toHaveTextContent("The operation could not be deleted.");
    expect(restoredFocus).toBe(cancel);
    expect(onCancel).toHaveBeenCalledOnce();
    expect(onConfirm).toHaveBeenCalledOnce();
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
