import { fireEvent, render, screen, waitFor } from "@solidjs/testing-library";
import { afterEach, describe, expect, it, vi } from "vitest";
import { App } from "../../src/app/App";
import type { GetOperationsResult, PlantId } from "../../src/domain/Journal";
import { operationId } from "../../src/domain/Journal";
import { buildJournal, care, ficus } from "./JournalTestSupport";

afterEach(() => Reflect.deleteProperty(document, "startViewTransition"));

describe("animating operation changes", () => {
  it("should not animate a refresh beneath a newly opened editor", async () => {
    const startViewTransition = vi.fn();
    Object.defineProperty(document, "startViewTransition", {
      configurable: true,
      value: startViewTransition,
    });
    let finishRefresh: (result: GetOperationsResult) => void = () => undefined;
    const refresh = new Promise<GetOperationsResult>((resolve) => {
      finishRefresh = resolve;
    });
    const base = buildJournal({
      getPlantsResult: { kind: "read", plants: [ficus()] },
      getOperationsByPlantId: { p1: [{ kind: "read", operations: [] }] },
      logOperationResult: { kind: "logged", id: operationId("new") },
    });
    let operationReads = 0;
    const journal = {
      ...base,
      getOperations: (plantId: PlantId) =>
        operationReads++ === 0 ? base.getOperations(plantId) : refresh,
    };
    render(() => <App journal={journal} />);
    await screen.findByRole("article", { name: "Fern" });

    fireEvent.click(screen.getByRole("button", { name: "Log operation for Fern" }));
    fireEvent.click(screen.getByRole("button", { name: "Save operation" }));
    await waitFor(() => {
      expect(operationReads).toBe(2);
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });
    fireEvent.click(screen.getByRole("button", { name: "Log operation for Fern" }));
    finishRefresh({
      kind: "read",
      operations: [care({ id: "new", date: "2026-05-05T00:00:00Z", moisture: "wet" })],
    });

    await screen.findByText("2026-05-05");
    expect(screen.getByRole("dialog", { name: "Operation editor" })).toBeInTheDocument();
    expect(startViewTransition).not.toHaveBeenCalled();
  });
});
