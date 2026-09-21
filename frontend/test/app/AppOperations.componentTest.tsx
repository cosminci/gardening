import { fireEvent, render, screen, waitFor, within } from "@solidjs/testing-library";
import { describe, expect, it } from "vitest";
import { App } from "../../src/app/App";
import type { OperationDetails } from "../../src/domain/Journal";
import { note, operationId, percentage, substrate } from "../../src/domain/Journal";
import { buildJournal, care, ficus, repot } from "./JournalTestSupport";

describe("changing the journal", () => {
  it("should log care and refresh the plant's operation history", async () => {
    const logged: { plantId: string; details: OperationDetails }[] = [];
    const journal = buildJournal({
      getPlantsResult: { kind: "read", plants: [ficus()] },
      getOperationsByPlantId: {
        p1: [
          { kind: "read", operations: [] },
          { kind: "read", operations: [care("new", "2026-05-05T00:00:00Z", "wet", "Recovered")] },
        ],
      },
      logOperationResult: { kind: "logged", id: operationId("new") },
      logged,
    });
    render(() => <App journal={journal} />);
    await screen.findByRole("article", { name: "Fern" });

    const trigger = screen.getByRole("button", { name: "Log operation for Fern" });
    trigger.focus();
    fireEvent.click(trigger);
    fireEvent.click(screen.getByRole("checkbox", { name: "Watered" }));
    fireEvent.change(screen.getByRole("combobox", { name: "Moisture" }), {
      target: { value: "wet" },
    });
    fireEvent.input(screen.getByRole("textbox", { name: "Notes" }), {
      target: { value: "Recovered" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Save operation" }));

    await screen.findByText("2026-05-05");
    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Log operation for Fern" })).toHaveFocus();
    });
    expect(logged).toEqual([
      {
        plantId: "p1",
        details: {
          kind: "care",
          actions: new Set(["watered"]),
          moisture: "wet",
          maybeNote: note("Recovered"),
        },
      },
    ]);
  });

  it("should edit repot details without allowing its operation type to change", async () => {
    const edited: { operationId: string; details: OperationDetails }[] = [];
    const existing = repot("o1", "2026-03-03T00:00:00Z");
    const updated = {
      ...existing,
      details: {
        kind: "repot" as const,
        substrate: substrate([{ component: "perlite", share: percentage(80) }]),
        maybeNote: note("Less perlite"),
      },
    };
    const journal = buildJournal({
      getPlantsResult: { kind: "read", plants: [ficus()] },
      getOperationsByPlantId: {
        p1: [
          { kind: "read", operations: [existing] },
          { kind: "read", operations: [updated] },
        ],
      },
      editOperationResult: { kind: "edited", operation: updated },
      edited,
    });
    render(() => <App journal={journal} />);
    await screen.findByText("2026-03-03");

    const trigger = screen.getByRole("button", {
      name: "Edit repot operation 1 from 2026-03-03",
    });
    trigger.focus();
    fireEvent.click(trigger);
    expect(screen.getByRole("combobox", { name: "Operation type" })).toBeDisabled();
    fireEvent.input(screen.getByRole("spinbutton", { name: "Component 1 share" }), {
      target: { value: "80" },
    });
    fireEvent.input(screen.getByRole("textbox", { name: "Notes" }), {
      target: { value: "Less perlite" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Save operation" }));

    await waitFor(() => {
      expect(edited).toEqual([
        {
          operationId: "o1",
          details: {
            kind: "repot",
            substrate: substrate([{ component: "perlite", share: percentage(80) }]),
            maybeNote: "Less perlite",
          },
        },
      ]);
    });
    expect(await screen.findByText("Less perlite")).toBeInTheDocument();
    await waitFor(() => {
      expect(
        screen.getByRole("button", { name: "Edit repot operation 1 from 2026-03-03" }),
      ).toHaveFocus();
    });
  });

  it("should close the operation editor with Escape", async () => {
    const journal = buildJournal({
      getPlantsResult: { kind: "read", plants: [ficus()] },
      getOperationsByPlantId: { p1: [{ kind: "read", operations: [] }] },
    });
    const { container } = render(() => <App journal={journal} />);
    const current = within(container);
    await current.findByRole("article", { name: "Fern" });
    const header = container.querySelector<HTMLElement>(".masthead")!;
    const journalRows = container.querySelector<HTMLElement>(".journal")!;

    const trigger = current.getByRole("button", { name: "Log operation for Fern" });
    trigger.focus();
    fireEvent.click(trigger);
    const dialog = current.getByRole("dialog", { name: "Operation editor" });
    expect(document.activeElement).toBe(dialog);
    expect(header.inert).toBe(true);
    expect(journalRows.inert).toBe(true);
    fireEvent.keyDown(window, { key: "Escape" });

    expect(current.queryByRole("dialog")).not.toBeInTheDocument();
    expect(header.inert).toBe(false);
    expect(journalRows.inert).toBe(false);
    expect(trigger).toHaveFocus();
  });
});
