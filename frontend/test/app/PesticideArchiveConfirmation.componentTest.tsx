import { fireEvent, render, screen, waitFor, within } from "@solidjs/testing-library";
import { describe, expect, it, vi } from "vitest";
import { PesticideArchiveConfirmation } from "../../src/app/PesticideArchiveConfirmation";
import * as Journal from "../../src/domain/Journal";

const neem: Journal.Pesticide = {
  id: Journal.pesticideId("00000000-0000-4000-8001-000000000003"),
  data: {
    name: Journal.pesticideName("Neem oil"),
    type: "insecticide",
    maybeInfo: null,
  },
  status: "active",
};

describe("pesticide archive confirmation", () => {
  it("should keep keyboard focus inside the permanent-archive warning", () => {
    const onCancel = vi.fn();
    render(() => (
      <PesticideArchiveConfirmation
        pesticide={neem}
        completed={false}
        onConfirm={() => Promise.resolve(undefined)}
        onCancel={onCancel}
      />
    ));
    const warning = screen.getByRole("alertdialog", { name: "Archive Neem oil" });
    const cancel = within(warning).getByRole("button", { name: "Cancel" });
    const confirm = within(warning).getByRole("button", { name: "Archive permanently" });

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
      <PesticideArchiveConfirmation
        pesticide={neem}
        completed={false}
        onConfirm={onConfirm}
        onCancel={onCancel}
      />
    ));

    const warning = screen.getByRole("alertdialog", { name: "Archive Neem oil" });
    const cancel = within(warning).getByRole("button", { name: "Cancel" });
    const confirm = within(warning).getByRole("button", { name: "Archive permanently" });

    fireEvent.click(confirm);
    const pendingFocus = document.activeElement;
    fireEvent.keyDown(window, { key: "Tab" });
    const trappedFocus = document.activeElement;
    fireEvent.keyDown(window, { key: "Escape" });
    const pendingCancelCalls = onCancel.mock.calls.length;
    finish("The pesticide could not be archived.");

    const alert = await screen.findByRole("alert");
    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Archive permanently" })).toBeEnabled();
    });
    const restoredFocus = document.activeElement;
    fireEvent.keyDown(window, { key: "Escape" });

    expect(pendingFocus).toBe(warning);
    expect(trappedFocus).toBe(warning);
    expect(pendingCancelCalls).toBe(0);
    expect(alert).toHaveTextContent("The pesticide could not be archived.");
    expect(restoredFocus).toBe(cancel);
    expect(onCancel).toHaveBeenCalledOnce();
    expect(onConfirm).toHaveBeenCalledOnce();
  });

  it("should restore focus to the pesticide's archive control on cancel, but not once completed", async () => {
    const opener = document.createElement("button");
    opener.id = `archive-pesticide-${neem.id}`;
    document.body.appendChild(opener);

    const cancelled = render(() => (
      <PesticideArchiveConfirmation
        pesticide={neem}
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
      <PesticideArchiveConfirmation
        pesticide={neem}
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
