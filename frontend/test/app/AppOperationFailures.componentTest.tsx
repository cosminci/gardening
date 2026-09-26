import * as Testing from "@solidjs/testing-library";
import * as Vitest from "vitest";
import { App } from "../../src/app/App";
import { instant, operationId } from "../../src/domain/Journal";
import type {
  AttentionProjection,
  GetPlantsResult,
  GetOperationsResult,
  OperationWindow,
  PlantId,
  PlantStatus,
} from "../../src/domain/Journal";
import { buildJournal, ficus, noopPhotoClient, operationsPage } from "./JournalTestSupport";

const unavailableFicusAttention: AttentionProjection = {
  measuredAt: instant("2026-01-01T00:00:00Z"),
  plants: [
    {
      plant: ficus().id,
      watering: { kind: "unavailable" as const, sampleCount: 0, maybeElapsed: null },
    },
  ],
};

Vitest.afterEach(() => Reflect.deleteProperty(document, "startViewTransition"));

Vitest.describe("operation failures", () => {
  Vitest.it("should report a logging failure without showing its reason", async () => {
    const reason = new Error("private details");
    const journal = buildJournal({
      attentionProjection: unavailableFicusAttention,
      getOperationsByPlantId: { p1: [operationsPage()] },
      logOperationResult: { kind: "loggingFailed", reason },
    });
    Testing.render(() => (
      <App
        plants={journal}
        operations={journal}
        attention={journal}
        substrates={journal}
        pesticideCatalog={journal}
        photos={noopPhotoClient}
      />
    ));
    await Testing.screen.findByRole("article", { name: "Fern" });

    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Log operation for Fern" }));
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Save operation" }));
    const alert = await Testing.screen.findByRole("alert");
    const privateReason = Testing.screen.queryByText("private details");
    Testing.fireEvent.click(
      Testing.screen.getByRole("button", { name: "Collapse operation editor" }),
    );
    await Testing.waitForElementToBeRemoved(() =>
      Testing.screen.queryByRole("form", { name: "Log operation" }),
    );

    Vitest.expect(alert).toHaveTextContent("The operation could not be saved.");
    Vitest.expect(privateReason).toBeNull();
    Vitest.expect(Testing.screen.queryByRole("form", { name: "Log operation" })).toBeNull();
  });

  Vitest.it(
    "should reject a new operation when its plant was archived while the form was open",
    async () => {
      const journal = buildJournal({
        attentionProjection: unavailableFicusAttention,
        getOperationsByPlantId: { p1: [operationsPage()] },
        logOperationResult: { kind: "plantArchived" },
      });
      Testing.render(() => (
        <App
          plants={journal}
          operations={journal}
          attention={journal}
          substrates={journal}
          pesticideCatalog={journal}
          photos={noopPhotoClient}
        />
      ));
      await Testing.screen.findByRole("button", { name: "Log operation for Fern" });

      Testing.fireEvent.click(
        Testing.screen.getByRole("button", { name: "Log operation for Fern" }),
      );
      Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Save operation" }));

      const alert = await Testing.screen.findByRole("alert");
      const editor = Testing.screen.getByRole("dialog", { name: "Operation editor" });

      const expectedMessage = "This plant is archived; new operations cannot be added.";
      Vitest.expect(alert).toHaveTextContent(expectedMessage);
      Vitest.expect(editor).toBeInTheDocument();
    },
  );

  Vitest.it("should report an unexpectedly rejected write", async () => {
    const journal = {
      ...buildJournal({
        attentionProjection: unavailableFicusAttention,
        getOperationsByPlantId: { p1: [operationsPage()] },
      }),
      logOperation: () => Promise.reject(new Error("private details")),
    };
    Testing.render(() => (
      <App
        plants={journal}
        operations={journal}
        attention={journal}
        substrates={journal}
        pesticideCatalog={journal}
        photos={noopPhotoClient}
      />
    ));
    await Testing.screen.findByRole("article", { name: "Fern" });

    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Log operation for Fern" }));
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Save operation" }));

    const alert = await Testing.screen.findByRole("alert");

    Vitest.expect(alert).toHaveTextContent("The operation could not be saved.");
    Vitest.expect(Testing.screen.queryByText("private details")).not.toBeInTheDocument();
  });

  Vitest.it("should report when the journal cannot refresh after saving", async () => {
    const base = buildJournal({
      attentionProjection: unavailableFicusAttention,
      getOperationsByPlantId: { p1: [operationsPage()] },
      logOperationResult: { kind: "logged", id: operationId("new") },
    });
    let plantReads = 0;
    const journal = {
      ...base,
      getPlants: () =>
        plantReads++ === 0 ? base.getPlants() : Promise.reject(new Error("private details")),
    };
    Testing.render(() => (
      <App
        plants={journal}
        operations={journal}
        attention={journal}
        substrates={journal}
        pesticideCatalog={journal}
        photos={noopPhotoClient}
      />
    ));
    await Testing.screen.findByRole("article", { name: "Fern" });

    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Log operation for Fern" }));
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Save operation" }));

    const alert = await Testing.screen.findByRole("alert");

    Vitest.expect(alert).toHaveTextContent("The journal could not be loaded.");
    Vitest.expect(Testing.screen.queryByText("private details")).not.toBeInTheDocument();
    Vitest.expect(Testing.screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  Vitest.it(
    "should ignore obsolete garden list and operation responses during cemetery browsing",
    async () => {
      let finishPlants: (result: GetPlantsResult) => void = () => undefined;
      const pendingPlants = new Promise<GetPlantsResult>((resolve) => {
        finishPlants = resolve;
      });
      let finishOperations: (result: GetOperationsResult) => void = () => undefined;
      const pendingOperations = new Promise<GetOperationsResult>((resolve) => {
        finishOperations = resolve;
      });
      const base = buildJournal({
        attentionProjection: unavailableFicusAttention,
        getOperationsByPlantId: { p1: [operationsPage()] },
      });
      let gardenReads = 0;
      let operationReads = 0;
      const journal = {
        ...base,
        getPlants: (status?: PlantStatus) =>
          status === "archived"
            ? Promise.resolve({ kind: "read" as const, plants: [] })
            : gardenReads++ === 1
              ? pendingPlants
              : base.getPlants(),
        getOperations: (id: PlantId, window: OperationWindow) =>
          operationReads++ === 1 ? pendingOperations : base.getOperations(id, window),
        logOperation: () => Promise.resolve({ kind: "logged" as const, id: operationId("new") }),
      };
      Testing.render(() => (
        <App
          plants={journal}
          operations={journal}
          attention={journal}
          substrates={journal}
          pesticideCatalog={journal}
          photos={noopPhotoClient}
        />
      ));
      await Testing.screen.findByRole("article", { name: "Fern" });

      Testing.fireEvent.click(
        Testing.screen.getByRole("button", { name: "Log operation for Fern" }),
      );
      Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Save operation" }));
      await Testing.waitFor(() => {
        Vitest.expect(gardenReads).toBe(2);
      });
      Testing.fireEvent.click(Testing.screen.getByRole("button", { name: /Cemetery.*0 plants/ }));
      finishPlants({ kind: "read", plants: [ficus()] });
      await pendingPlants;
      Testing.fireEvent.click(Testing.screen.getByRole("button", { name: /Garden.*1 plant/ }));
      Testing.fireEvent.click(
        Testing.screen.getByRole("button", { name: "Log operation for Fern" }),
      );
      Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Save operation" }));
      await Testing.waitFor(() => {
        Vitest.expect(operationReads).toBe(2);
      });
      Testing.fireEvent.click(Testing.screen.getByRole("button", { name: /Cemetery.*0 plants/ }));
      finishOperations(operationsPage());
      await pendingOperations;
      await Promise.resolve();

      Vitest.expect(Testing.screen.getByRole("region", { name: "Cemetery" })).toBeInTheDocument();
      Vitest.expect(Testing.screen.queryByRole("alert")).toBeNull();
    },
  );

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
      attentionProjection: unavailableFicusAttention,
      getOperationsByPlantId: { p1: [operationsPage()] },
    });
    let plantReads = 0;
    const journal = {
      ...base,
      getPlants: () => {
        plantReads++;
        return base.getPlants();
      },
      logOperation: () => saving,
    };
    Testing.render(() => (
      <App
        plants={journal}
        operations={journal}
        attention={journal}
        substrates={journal}
        pesticideCatalog={journal}
        photos={noopPhotoClient}
      />
    ));
    await Testing.screen.findByRole("article", { name: "Fern" });

    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Log operation for Fern" }));
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Save operation" }));
    Testing.fireEvent.keyDown(window, { key: "Escape" });
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Log operation for Fern" }));
    finishSaving({ kind: "logged", id: operationId("new") });

    const editor = await Testing.screen.findByRole("dialog", { name: "Operation editor" });
    await Testing.waitFor(() => {
      Vitest.expect(plantReads).toBe(2);
    });

    Vitest.expect(editor).toBeInTheDocument();
    Vitest.expect(startViewTransition).not.toHaveBeenCalled();
  });

  Vitest.it(
    "should ignore a stale post-save refresh failure after leaving the garden",
    async () => {
      let rejectGarden: (reason: Error) => void = () => undefined;
      const pendingGarden = new Promise<GetPlantsResult>((_resolve, reject) => {
        rejectGarden = reject;
      });
      const base = buildJournal({
        attentionProjection: unavailableFicusAttention,
        getOperationsByPlantId: { p1: [operationsPage()] },
        logOperationResult: { kind: "logged", id: operationId("new") },
      });
      let gardenReads = 0;
      const journal = {
        ...base,
        getPlants: (status?: PlantStatus) =>
          status === "archived"
            ? Promise.resolve({ kind: "read" as const, plants: [] })
            : gardenReads++ === 0
              ? base.getPlants()
              : pendingGarden,
      };
      Testing.render(() => (
        <App
          plants={journal}
          operations={journal}
          attention={journal}
          substrates={journal}
          pesticideCatalog={journal}
          photos={noopPhotoClient}
        />
      ));
      await Testing.screen.findByRole("article", { name: "Fern" });

      Testing.fireEvent.click(
        Testing.screen.getByRole("button", { name: "Log operation for Fern" }),
      );
      Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Save operation" }));
      await Testing.waitFor(() => {
        Vitest.expect(gardenReads).toBe(2);
      });

      Testing.fireEvent.click(Testing.screen.getByRole("button", { name: /Cemetery.*0 plants/ }));
      await Testing.screen.findByRole("region", { name: "Cemetery" });
      rejectGarden(new Error("stale refresh"));
      await pendingGarden.catch(() => undefined);
      await Promise.resolve();

      Vitest.expect(Testing.screen.getByRole("region", { name: "Cemetery" })).toBeInTheDocument();
      Vitest.expect(Testing.screen.queryByRole("alert")).toBeNull();
    },
  );
});
