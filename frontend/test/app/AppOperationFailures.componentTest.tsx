import { fireEvent, render, screen, waitFor } from "@solidjs/testing-library";
import { afterEach, describe, expect, it, vi } from "vitest";
import { App } from "../../src/app/App";
import { operationId } from "../../src/domain/Journal";
import { buildJournal, ficus } from "./JournalTestSupport";

afterEach(() => Reflect.deleteProperty(document, "startViewTransition"));

describe("operation failures", () => {
  it("should report a logging failure without showing its reason", async () => {
    const reason = new Error("private details");
    const journal = buildJournal({
      getPlantsResult: { kind: "read", plants: [ficus()] },
      getOperationsByPlantId: { p1: [{ kind: "read", operations: [] }] },
      logOperationResult: { kind: "loggingFailed", reason },
    });
    render(() => <App journal={journal} />);
    await screen.findByRole("article", { name: "Fern" });

    fireEvent.click(screen.getByRole("button", { name: "Log operation for Fern" }));
    fireEvent.click(screen.getByRole("button", { name: "Save operation" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("The operation could not be saved.");
    expect(screen.queryByText("private details")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Collapse operation editor" }));
    expect(screen.queryByRole("form", { name: "Log operation" })).not.toBeInTheDocument();
  });

  it("should report an unexpectedly rejected write", async () => {
    const journal = {
      ...buildJournal({
        getPlantsResult: { kind: "read", plants: [ficus()] },
        getOperationsByPlantId: { p1: [{ kind: "read", operations: [] }] },
      }),
      logOperation: () => Promise.reject(new Error("private details")),
    };
    render(() => <App journal={journal} />);
    await screen.findByRole("article", { name: "Fern" });

    fireEvent.click(screen.getByRole("button", { name: "Log operation for Fern" }));
    fireEvent.click(screen.getByRole("button", { name: "Save operation" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("The operation could not be saved.");
    expect(screen.queryByText("private details")).not.toBeInTheDocument();
  });

  it("should report when the journal cannot refresh after saving", async () => {
    const base = buildJournal({
      getPlantsResult: { kind: "read", plants: [ficus()] },
      getOperationsByPlantId: { p1: [{ kind: "read", operations: [] }] },
      logOperationResult: { kind: "logged", id: operationId("new") },
    });
    let plantReads = 0;
    const journal = {
      ...base,
      getPlants: () =>
        plantReads++ === 0 ? base.getPlants() : Promise.reject(new Error("private details")),
    };
    render(() => <App journal={journal} />);
    await screen.findByRole("article", { name: "Fern" });

    fireEvent.click(screen.getByRole("button", { name: "Log operation for Fern" }));
    fireEvent.click(screen.getByRole("button", { name: "Save operation" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("The journal could not be loaded.");
    expect(screen.queryByText("private details")).not.toBeInTheDocument();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("should not close a new form when an earlier save completes", async () => {
    const startViewTransition = vi.fn();
    Object.defineProperty(document, "startViewTransition", {
      configurable: true,
      value: startViewTransition,
    });
    let finishSaving: (result: {
      kind: "logged";
      id: ReturnType<typeof operationId>;
    }) => void = () => undefined;
    const saving = new Promise<{ kind: "logged"; id: ReturnType<typeof operationId> }>(
      (resolve) => {
        finishSaving = resolve;
      },
    );
    const base = buildJournal({
      getPlantsResult: { kind: "read", plants: [ficus()] },
      getOperationsByPlantId: { p1: [{ kind: "read", operations: [] }] },
    });
    let plantReads = 0;
    const journal = {
      ...base,
      getPlants: () => {
        plantReads += 1;
        return base.getPlants();
      },
      logOperation: () => saving,
    };
    render(() => <App journal={journal} />);
    await screen.findByRole("article", { name: "Fern" });

    fireEvent.click(screen.getByRole("button", { name: "Log operation for Fern" }));
    fireEvent.click(screen.getByRole("button", { name: "Save operation" }));
    fireEvent.keyDown(window, { key: "Escape" });
    fireEvent.click(screen.getByRole("button", { name: "Log operation for Fern" }));
    finishSaving({ kind: "logged", id: operationId("new") });

    await waitFor(() => {
      expect(plantReads).toBe(2);
      expect(screen.getByRole("dialog", { name: "Operation editor" })).toBeInTheDocument();
    });
    expect(startViewTransition).not.toHaveBeenCalled();
  });
});
