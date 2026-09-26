import { fireEvent, render, screen, waitFor, within } from "@solidjs/testing-library";
import { describe, expect, it, vi } from "vitest";
import { ArchiveConfirmation } from "../../src/app/ArchiveConfirmation";
import { ficus } from "./JournalTestSupport";

describe("archive confirmation", () => {
  it("should keep keyboard focus inside the permanent-archive warning", () => {
    const onCancel = vi.fn();
    render(() => (
      <ArchiveConfirmation
        plant={ficus()}
        completed={false}
        onConfirm={() => Promise.resolve(undefined)}
        onCancel={onCancel}
      />
    ));
    const warning = screen.getByRole("alertdialog", { name: "Move Fern to cemetery" });
    const cancel = within(warning).getByRole("button", { name: "Cancel" });
    const confirm = within(warning).getByRole("button", { name: "Move to cemetery" });

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

  it("should prevent duplicate archiving while the request is pending and show failures", async () => {
    let finish: (message: string) => void = () => undefined;
    const onConfirm = vi.fn(
      () =>
        new Promise<string>((resolve) => {
          finish = resolve;
        }),
    );
    const onCancel = vi.fn();
    render(() => (
      <ArchiveConfirmation
        plant={ficus()}
        completed={false}
        onConfirm={onConfirm}
        onCancel={onCancel}
      />
    ));

    const warning = screen.getByRole("alertdialog", { name: "Move Fern to cemetery" });
    const cancel = within(warning).getByRole("button", { name: "Cancel" });
    const confirm = within(warning).getByRole("button", { name: "Move to cemetery" });

    fireEvent.click(confirm);
    const pendingFocus = document.activeElement;
    fireEvent.keyDown(window, { key: "Tab" });
    const trappedFocus = document.activeElement;
    fireEvent.keyDown(window, { key: "Escape" });
    const pendingCancelCalls = onCancel.mock.calls.length;
    finish("The plant could not be archived.");

    const alert = await screen.findByRole("alert");
    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Move to cemetery" })).toBeEnabled();
    });
    const restoredFocus = document.activeElement;
    fireEvent.keyDown(window, { key: "Escape" });

    expect(pendingFocus).toBe(warning);
    expect(trappedFocus).toBe(warning);
    expect(pendingCancelCalls).toBe(0);
    expect(alert).toHaveTextContent("The plant could not be archived.");
    expect(restoredFocus).toBe(cancel);
    expect(onCancel).toHaveBeenCalledOnce();
    expect(onConfirm).toHaveBeenCalledOnce();
  });
});
