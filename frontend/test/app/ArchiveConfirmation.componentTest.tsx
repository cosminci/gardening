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
    const warning = screen.getByRole("alertdialog", { name: "Archive Fern" });
    const cancel = within(warning).getByRole("button", { name: "Cancel" });
    const confirm = within(warning).getByRole("button", { name: "Archive permanently" });

    expect(cancel).toHaveFocus();
    fireEvent.keyDown(window, { key: "Tab", shiftKey: true });
    expect(confirm).toHaveFocus();
    fireEvent.keyDown(window, { key: "Tab" });
    expect(cancel).toHaveFocus();
    fireEvent.keyDown(window, { key: "Escape" });
    expect(onCancel).toHaveBeenCalledOnce();
    fireEvent.click(cancel);
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

    const warning = screen.getByRole("alertdialog", { name: "Archive Fern" });
    const cancel = within(warning).getByRole("button", { name: "Cancel" });
    const confirm = within(warning).getByRole("button", { name: "Archive permanently" });

    fireEvent.click(confirm);
    expect(warning).toHaveFocus();
    fireEvent.keyDown(window, { key: "Tab" });
    expect(warning).toHaveFocus();
    fireEvent.keyDown(window, { key: "Escape" });
    finish("The plant could not be archived.");

    expect(onCancel).not.toHaveBeenCalled();
    expect(await screen.findByRole("alert")).toHaveTextContent("The plant could not be archived.");
    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Archive permanently" })).toBeEnabled();
    });
    expect(cancel).toHaveFocus();
    fireEvent.keyDown(window, { key: "Escape" });
    expect(onCancel).toHaveBeenCalledOnce();
    expect(onConfirm).toHaveBeenCalledOnce();
  });
});
