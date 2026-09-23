import * as Testing from "@solidjs/testing-library";
import * as Vitest from "vitest";
import { App } from "../../src/app/App";
import type { GetOperationsResult, OperationWindow, PlantId } from "../../src/domain/Journal";
import { instant, operationId } from "../../src/domain/Journal";
import { buildJournal, care, ficus, operationsPage } from "./JournalTestSupport";

Vitest.afterEach(() => Reflect.deleteProperty(document, "startViewTransition"));

const unavailableFicusAttentionResult = {
  kind: "read" as const,
  projection: {
    measuredAt: instant("2026-01-01T00:00:00Z"),
    plants: [
      {
        plant: ficus(),
        watering: { kind: "unavailable" as const, sampleCount: 0, maybeElapsed: null },
      },
    ],
  },
};

Vitest.describe("animating operation changes", () => {
  Vitest.it("should not animate a refresh beneath a newly opened editor", async () => {
    const startViewTransition = Vitest.vi.fn();
    Object.defineProperty(document, "startViewTransition", {
      configurable: true,
      value: startViewTransition,
    });
    let finishRefresh: (result: GetOperationsResult) => void = () => undefined;
    const refresh = new Promise<GetOperationsResult>((resolve) => {
      finishRefresh = resolve;
    });
    const base = buildJournal({
      getAttentionResults: [unavailableFicusAttentionResult],
      getOperationsByPlantId: { p1: [operationsPage()] },
      logOperationResult: { kind: "logged", id: operationId("new") },
    });
    let operationReads = 0;
    const journal = {
      ...base,
      getOperations: (plantId: PlantId, window: OperationWindow) =>
        operationReads++ === 0 ? base.getOperations(plantId, window) : refresh,
    };
    Testing.render(() => <App journal={journal} />);
    await Testing.screen.findByRole("article", { name: "Fern" });

    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Log operation for Fern" }));
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Save operation" }));
    await Testing.waitFor(() => {
      Vitest.expect(operationReads).toBe(2);
      Vitest.expect(Testing.screen.queryByRole("dialog")).not.toBeInTheDocument();
    });
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Log operation for Fern" }));
    finishRefresh(
      operationsPage([care({ id: "new", date: "2026-05-05T00:00:00Z", moisture: "wet" })]),
    );

    await Testing.screen.findByText("2026-05-05");
    Vitest.expect(
      Testing.screen.getByRole("dialog", { name: "Operation editor" }),
    ).toBeInTheDocument();
    Vitest.expect(startViewTransition).not.toHaveBeenCalled();
  });
});
