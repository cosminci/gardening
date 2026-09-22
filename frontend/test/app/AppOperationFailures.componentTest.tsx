import * as Testing from "@solidjs/testing-library";
import * as Vitest from "vitest";
import { App } from "../../src/app/App";
import { operationId } from "../../src/domain/Journal";
import { buildJournal, ficus, operationsPage } from "./JournalTestSupport";

Vitest.afterEach(() => Reflect.deleteProperty(document, "startViewTransition"));

Vitest.describe("operation failures", () => {
  Vitest.it("should report a logging failure without showing its reason", async () => {
    const reason = new Error("private details");
    const journal = buildJournal({
      getPlantsResult: { kind: "read", plants: [ficus()] },
      getOperationsByPlantId: { p1: [operationsPage()] },
      logOperationResult: { kind: "loggingFailed", reason },
    });
    Testing.render(() => <App journal={journal} />);
    await Testing.screen.findByRole("article", { name: "Fern" });

    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Log operation for Fern" }));
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Save operation" }));

    Vitest.expect(await Testing.screen.findByRole("alert")).toHaveTextContent(
      "The operation could not be saved.",
    );
    Vitest.expect(Testing.screen.queryByText("private details")).not.toBeInTheDocument();
    Testing.fireEvent.click(
      Testing.screen.getByRole("button", { name: "Collapse operation editor" }),
    );
    await Testing.waitFor(() => {
      Vitest.expect(
        Testing.screen.queryByRole("form", { name: "Log operation" }),
      ).not.toBeInTheDocument();
    });
  });

  Vitest.it("should report an unexpectedly rejected write", async () => {
    const journal = {
      ...buildJournal({
        getPlantsResult: { kind: "read", plants: [ficus()] },
        getOperationsByPlantId: { p1: [operationsPage()] },
      }),
      logOperation: () => Promise.reject(new Error("private details")),
    };
    Testing.render(() => <App journal={journal} />);
    await Testing.screen.findByRole("article", { name: "Fern" });

    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Log operation for Fern" }));
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Save operation" }));

    Vitest.expect(await Testing.screen.findByRole("alert")).toHaveTextContent(
      "The operation could not be saved.",
    );
    Vitest.expect(Testing.screen.queryByText("private details")).not.toBeInTheDocument();
  });

  Vitest.it("should report when the journal cannot refresh after saving", async () => {
    const base = buildJournal({
      getPlantsResult: { kind: "read", plants: [ficus()] },
      getOperationsByPlantId: { p1: [operationsPage()] },
      logOperationResult: { kind: "logged", id: operationId("new") },
    });
    let plantReads = 0;
    const journal = {
      ...base,
      getPlants: () =>
        plantReads++ === 0 ? base.getPlants() : Promise.reject(new Error("private details")),
    };
    Testing.render(() => <App journal={journal} />);
    await Testing.screen.findByRole("article", { name: "Fern" });

    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Log operation for Fern" }));
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Save operation" }));

    Vitest.expect(await Testing.screen.findByRole("alert")).toHaveTextContent(
      "The journal could not be loaded.",
    );
    Vitest.expect(Testing.screen.queryByText("private details")).not.toBeInTheDocument();
    Vitest.expect(Testing.screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  Vitest.it("should not close a new form when an earlier save completes", async () => {
    const startViewTransition = Vitest.vi.fn();
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
      getOperationsByPlantId: { p1: [operationsPage()] },
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
    Testing.render(() => <App journal={journal} />);
    await Testing.screen.findByRole("article", { name: "Fern" });

    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Log operation for Fern" }));
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Save operation" }));
    Testing.fireEvent.keyDown(window, { key: "Escape" });
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Log operation for Fern" }));
    finishSaving({ kind: "logged", id: operationId("new") });

    await Testing.waitFor(() => {
      Vitest.expect(plantReads).toBe(2);
      Vitest.expect(
        Testing.screen.getByRole("dialog", { name: "Operation editor" }),
      ).toBeInTheDocument();
    });
    Vitest.expect(startViewTransition).not.toHaveBeenCalled();
  });
});
