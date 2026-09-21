import { fireEvent, render, screen } from "@solidjs/testing-library";
import { describe, expect, it } from "vitest";
import { App } from "../../src/app/App";
import { buildJournal, ficus, repot } from "./JournalTestSupport";

describe("operation edit failures", () => {
  it("should report when an operation no longer exists", async () => {
    const existing = repot("o1", "2026-03-03T00:00:00Z");
    const journal = buildJournal({
      getPlantsResult: { kind: "read", plants: [ficus()] },
      getOperationsByPlantId: { p1: [{ kind: "read", operations: [existing] }] },
      editOperationResult: { kind: "operationMissing" },
    });
    render(() => <App journal={journal} />);
    await screen.findByText("2026-03-03");

    fireEvent.click(screen.getByRole("button", { name: "Edit repot operation 1 from 2026-03-03" }));
    fireEvent.click(screen.getByRole("button", { name: "Save operation" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("This operation no longer exists.");
  });

  it("should report an attempt to change an operation type", async () => {
    const existing = repot("o1", "2026-03-03T00:00:00Z");
    const journal = buildJournal({
      getPlantsResult: { kind: "read", plants: [ficus()] },
      getOperationsByPlantId: { p1: [{ kind: "read", operations: [existing] }] },
      editOperationResult: { kind: "operationTypeMismatch" },
    });
    render(() => <App journal={journal} />);
    await screen.findByText("2026-03-03");

    fireEvent.click(screen.getByRole("button", { name: "Edit repot operation 1 from 2026-03-03" }));
    fireEvent.click(screen.getByRole("button", { name: "Save operation" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "The operation type cannot be changed.",
    );
  });

  it("should report an edit failure without showing its reason", async () => {
    const reason = new Error("private details");
    const existing = repot("o1", "2026-03-03T00:00:00Z");
    const journal = buildJournal({
      getPlantsResult: { kind: "read", plants: [ficus()] },
      getOperationsByPlantId: { p1: [{ kind: "read", operations: [existing] }] },
      editOperationResult: { kind: "editFailed", reason },
    });
    render(() => <App journal={journal} />);
    await screen.findByText("2026-03-03");

    fireEvent.click(screen.getByRole("button", { name: "Edit repot operation 1 from 2026-03-03" }));
    fireEvent.click(screen.getByRole("button", { name: "Save operation" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("The operation could not be saved.");
    expect(screen.queryByText("private details")).not.toBeInTheDocument();
  });
});
