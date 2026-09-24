import * as Testing from "@solidjs/testing-library";
import * as Vitest from "vitest";
import { App } from "../../src/app/App";
import * as Journal from "../../src/domain/Journal";
import * as JournalFixtures from "./JournalTestSupport";

Vitest.afterEach(() => Reflect.deleteProperty(document, "startViewTransition"));

const perliteId = Journal.substrateComponentId("00000000-0000-4000-8000-000000000003");

const unavailableFicusAttentionResult: Journal.GetAttentionResult = {
  kind: "read",
  projection: {
    measuredAt: Journal.instant("2026-01-01T00:00:00Z"),
    plants: [
      {
        plantId: JournalFixtures.ficus().id,
        watering: { kind: "unavailable", sampleCount: 0, maybeElapsed: null },
      },
    ],
  },
};

Vitest.describe("changing the journal", () => {
  Vitest.it(
    "should archive a plant and refresh both populations without guessing counts",
    async () => {
      const activePlant = JournalFixtures.ficus();
      const statuses: (Journal.PlantStatus | undefined)[] = [];
      let countReads = 0;
      const archivePlant = Vitest.vi.fn(() => Promise.resolve({ kind: "archived" as const }));
      const base = JournalFixtures.buildJournal({
        getAttentionResults: [unavailableFicusAttentionResult],
        getPlantsResults: [
          { kind: "read", plants: [activePlant] },
          { kind: "read", plants: [] },
        ],
        getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
      });
      const journal = {
        ...base,
        getPlants: (status?: Journal.PlantStatus) => {
          statuses.push(status);
          return base.getPlants(status);
        },
        getArchivedCount: () => Promise.resolve({ kind: "read" as const, count: countReads++ }),
        archivePlant,
      };
      Testing.render(() => (
        <App journal={journal} substrates={journal} pesticideCatalog={journal} />
      ));
      const archiveControl = await Testing.screen.findByRole("button", { name: "Archive Fern" });

      Testing.fireEvent.click(archiveControl);
      const warning = Testing.screen.getByRole("alertdialog");
      Testing.fireEvent.click(
        Testing.within(warning).getByRole("button", { name: "Archive permanently" }),
      );
      const garden = await Testing.screen.findByRole("button", { name: /Garden.*0 plants/ });
      await Testing.waitFor(() => {
        Vitest.expect(garden).toHaveFocus();
      });
      const gardenFocused = document.activeElement === garden;
      const cemetery = Testing.screen.getByRole("button", { name: /Cemetery.*1 plant/ });
      const gardenCard = Testing.screen.queryByRole("article", { name: "Fern" });

      Vitest.expect(warning).toHaveTextContent("cannot be undone");
      Vitest.expect(gardenFocused).toBe(true);
      Vitest.expect(cemetery).toBeInTheDocument();
      Vitest.expect(gardenCard).toBeNull();
      Vitest.expect(statuses).toEqual([undefined, undefined]);
      Vitest.expect(countReads).toBe(2);
      Vitest.expect(archivePlant).toHaveBeenCalledWith(activePlant.id);
    },
  );

  Vitest.it("should cancel archiving and restore focus without writing", async () => {
    const archivePlant = Vitest.vi.fn(() => Promise.resolve({ kind: "archived" as const }));
    const journal = {
      ...JournalFixtures.buildJournal({
        getAttentionResults: [unavailableFicusAttentionResult],
        getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
      }),
      archivePlant,
    };
    Testing.render(() => <App journal={journal} substrates={journal} pesticideCatalog={journal} />);
    const archiveControl = await Testing.screen.findByRole("button", { name: "Archive Fern" });
    archiveControl.focus();

    Testing.fireEvent.click(archiveControl);
    const warning = Testing.screen.getByRole("alertdialog");
    const warningText = warning.textContent;
    Testing.fireEvent.click(Testing.within(warning).getByRole("button", { name: "Cancel" }));

    Vitest.expect(warningText).toContain("cannot be undone");
    Vitest.expect(archivePlant).not.toHaveBeenCalled();
    Vitest.expect(archiveControl).toHaveFocus();
    Vitest.expect(Testing.screen.getByRole("article", { name: "Fern" })).toBeInTheDocument();
    Vitest.expect(Testing.screen.queryByRole("alertdialog")).toBeNull();
  });

  Vitest.it("should keep another plant usable as archived attention catches up", async () => {
    const ficusPlant = JournalFixtures.ficus();
    const monsteraPlant = JournalFixtures.monstera();
    const attention = {
      kind: "read" as const,
      projection: {
        measuredAt: Journal.instant("2026-01-01T00:00:00Z"),
        plants: [ficusPlant, monsteraPlant].map((plant) => ({
          plantId: plant.id,
          watering: { kind: "unavailable" as const, sampleCount: 0, maybeElapsed: null },
        })),
      },
    };
    const base = JournalFixtures.buildJournal({
      getAttentionResults: [
        attention,
        attention,
        {
          ...attention,
          projection: {
            ...attention.projection,
            plants: attention.projection.plants.filter(
              (sample) => sample.plantId === monsteraPlant.id,
            ),
          },
        },
      ],
      getPlantsResults: [
        { kind: "read", plants: [ficusPlant, monsteraPlant] },
        { kind: "read", plants: [monsteraPlant] },
      ],
      getOperationsByPlantId: {
        p1: [JournalFixtures.operationsPage()],
        p2: [JournalFixtures.operationsPage()],
      },
    });
    let attentionReads = 0;
    const journal = {
      ...base,
      getAttention: () => {
        attentionReads += 1;
        return base.getAttention();
      },
      getArchivedCount: () => Promise.resolve({ kind: "read" as const, count: 1 }),
      archivePlant: () => Promise.resolve({ kind: "archived" as const }),
      logOperation: () =>
        Promise.resolve({ kind: "logged" as const, id: Journal.operationId("recorded") }),
    };
    Testing.render(() => <App journal={journal} substrates={journal} pesticideCatalog={journal} />);
    const archiveControl = await Testing.screen.findByRole("button", { name: "Archive Fern" });
    archiveControl.focus();

    Testing.fireEvent.click(archiveControl);
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Archive permanently" }));
    const garden = await Testing.screen.findByRole("button", { name: /Garden.*1 plant/ });
    await Testing.waitFor(() => {
      Vitest.expect(garden).toHaveFocus();
    });
    const archiveRemaining = Testing.screen.getByRole("button", {
      name: "Archive Monstera deliciosa",
    });
    const gardenFocused = document.activeElement === garden;
    const remainingFocused = document.activeElement === archiveRemaining;
    Testing.fireEvent.click(
      Testing.screen.getByRole("button", { name: "Log operation for Monstera deliciosa" }),
    );
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Save operation" }));
    await Testing.waitFor(() => {
      Vitest.expect(attentionReads).toBe(3);
    });
    const remainingCard = Testing.screen.getByRole("article", { name: "Monstera deliciosa" });

    Vitest.expect(gardenFocused).toBe(true);
    Vitest.expect(remainingFocused).toBe(false);
    Vitest.expect(remainingCard).toBeInTheDocument();
    Vitest.expect(Testing.screen.queryByRole("alert")).toBeNull();
  });

  Vitest.it("should keep the archive warning open when the plant cannot be archived", async () => {
    const cases = [
      { outcome: { kind: "plantMissing" as const }, message: "This plant no longer exists." },
      {
        outcome: { kind: "alreadyArchived" as const },
        message: "This plant was already archived.",
      },
      {
        outcome: { kind: "archiveFailed" as const, reason: new Error("private details") },
        message: "The plant could not be archived.",
      },
      { outcome: new Error("connection interrupted"), message: "The plant could not be archived." },
    ];
    for (const { outcome, message } of cases) {
      const base = JournalFixtures.buildJournal({
        getAttentionResults: [unavailableFicusAttentionResult],
        getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
      });
      const journal = {
        ...base,
        archivePlant: () =>
          outcome instanceof Error ? Promise.reject(outcome) : Promise.resolve(outcome),
      };
      const view = Testing.render(() => (
        <App journal={journal} substrates={journal} pesticideCatalog={journal} />
      ));
      await Testing.screen.findByRole("button", { name: "Archive Fern" });

      Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Archive Fern" }));
      Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Archive permanently" }));

      const alert = await Testing.screen.findByRole("alert");

      Vitest.expect(alert).toHaveTextContent(message);
      Vitest.expect(Testing.screen.getByRole("article", { name: "Fern" })).toBeInTheDocument();
      Vitest.expect(Testing.screen.getByRole("alertdialog")).toBeInTheDocument();
      Vitest.expect(Testing.screen.queryByText("private details")).toBeNull();
      view.unmount();
    }
  });

  Vitest.it(
    "should show a failed journal read after a successful archive cannot be refreshed",
    async () => {
      const base = JournalFixtures.buildJournal({
        getAttentionResults: [unavailableFicusAttentionResult],
        getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
      });
      let plantReads = 0;
      const journal = {
        ...base,
        getPlants: () =>
          plantReads++ === 0 ? base.getPlants() : Promise.reject(new Error("offline")),
        archivePlant: () => Promise.resolve({ kind: "archived" as const }),
      };
      Testing.render(() => (
        <App journal={journal} substrates={journal} pesticideCatalog={journal} />
      ));
      await Testing.screen.findByRole("article", { name: "Fern" });

      Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Archive Fern" }));
      Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Archive permanently" }));

      const alert = await Testing.screen.findByRole("alert");

      Vitest.expect(alert).toHaveTextContent("The journal could not be loaded.");
      Vitest.expect(Testing.screen.queryByRole("alertdialog")).toBeNull();
    },
  );

  Vitest.it("should edit existing cemetery history without offering new operations", async () => {
    const archivedPlant = {
      ...JournalFixtures.ficus(),
      details: { ...JournalFixtures.ficus().details, status: "archived" as const },
    };
    const recordedOperation = JournalFixtures.care({
      id: "recorded",
      date: "2026-03-03T08:00:00Z",
      moisture: "dry",
    });
    const editedOperation = {
      ...recordedOperation,
      details: { ...recordedOperation.details, maybeNote: Journal.note("Recovered") },
    };
    const journal = {
      ...JournalFixtures.buildJournal({
        getPlantsResults: [
          { kind: "read", plants: [] },
          { kind: "read", plants: [archivedPlant] },
          { kind: "read", plants: [archivedPlant] },
        ],
        getOperationsByPlantId: {
          p1: [
            JournalFixtures.operationsPage([recordedOperation]),
            JournalFixtures.operationsPage([editedOperation]),
          ],
        },
        editOperationResult: { kind: "edited", operation: editedOperation },
      }),
      getArchivedCount: () => Promise.resolve({ kind: "read" as const, count: 1 }),
      getOperationDates: () =>
        Promise.resolve({
          kind: "read" as const,
          dates: {
            kind: "recorded" as const,
            first: recordedOperation.date,
            last: recordedOperation.date,
          },
        }),
    };
    Testing.render(() => <App journal={journal} substrates={journal} pesticideCatalog={journal} />);
    Testing.fireEvent.click(
      await Testing.screen.findByRole("button", { name: /Cemetery.*1 plant/ }),
    );
    const card = await Testing.screen.findByRole("article", { name: "Fern" });

    Testing.fireEvent.click(
      Testing.within(card).getByRole("button", { name: /Edit recent care operation/ }),
    );
    Testing.fireEvent.input(Testing.screen.getByRole("textbox", { name: "Notes" }), {
      target: { value: "Recovered" },
    });
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Save operation" }));

    Vitest.expect(await Testing.within(card).findByText("Recovered")).toBeInTheDocument();
    Vitest.expect(Testing.within(card).queryByRole("button", { name: /Log operation/ })).toBeNull();
  });

  Vitest.it("should log care and refresh the plant's operation history", async () => {
    const startViewTransition = Vitest.vi.fn((update: () => void) => {
      update();
      return { updateCallbackDone: Promise.resolve() } as ViewTransition;
    });
    Object.defineProperty(document, "startViewTransition", {
      configurable: true,
      value: startViewTransition,
    });
    const logged: { plantId: string; date: Journal.Instant; details: Journal.OperationDetails }[] =
      [];
    const journal = JournalFixtures.buildJournal({
      getAttentionResults: [unavailableFicusAttentionResult],
      getOperationsByPlantId: {
        p1: [
          JournalFixtures.operationsPage(),
          JournalFixtures.operationsPage([
            JournalFixtures.care({
              id: "new",
              date: "2026-05-05T00:00:00Z",
              moisture: "wet",
              maybeNote: "Recovered",
            }),
          ]),
        ],
      },
      logOperationResult: { kind: "logged", id: Journal.operationId("new") },
      logged,
    });
    Testing.render(() => <App journal={journal} substrates={journal} pesticideCatalog={journal} />);
    await Testing.screen.findByRole("article", { name: "Fern" });

    const trigger = Testing.screen.getByRole("button", { name: "Log operation for Fern" });
    trigger.focus();
    Testing.fireEvent.click(trigger);
    Testing.fireEvent.click(Testing.screen.getByRole("checkbox", { name: "Watered" }));
    Testing.fireEvent.change(Testing.screen.getByRole("combobox", { name: "Moisture" }), {
      target: { value: "wet" },
    });
    Testing.fireEvent.input(Testing.screen.getByRole("textbox", { name: "Notes" }), {
      target: { value: "Recovered" },
    });
    const selectedDate = Testing.screen.getByLabelText<HTMLInputElement>("Date and time").value;
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Save operation" }));

    await Testing.screen.findByText("5th of May");
    const logControl = Testing.screen.getByRole("button", { name: "Log operation for Fern" });
    await Testing.waitFor(() => Vitest.expect(logControl).toHaveFocus());
    const expectedOperation: Journal.OperationDetails = {
      kind: "care",
      actions: new Set(["watered"]),
      pesticides: new Set(),
      moisture: "wet",
      maybeNote: Journal.note("Recovered"),
    };
    const expectedLogged = [
      {
        plantId: "p1",
        date: Journal.instant(new Date(selectedDate).toISOString()),
        details: expectedOperation,
      },
    ];
    Vitest.expect(startViewTransition).toHaveBeenCalledOnce();
    Vitest.expect(logged).toEqual(expectedLogged);
  });

  Vitest.it("should edit repot details without allowing its operation type to change", async () => {
    const edited: { operationId: string; details: Journal.OperationDetails }[] = [];
    const existing = JournalFixtures.repot("o1", "2026-03-03T00:00:00Z");
    const updated = {
      ...existing,
      details: {
        kind: "repot" as const,
        substrate: Journal.substrate([{ component: perliteId, share: Journal.percentage(80) }]),
        maybeNote: Journal.note("Less perlite"),
      },
    };
    const currentPlant = JournalFixtures.ficus();
    const freshPlant = {
      ...currentPlant,
      details: { ...currentPlant.details, substrate: updated.details.substrate },
    };
    const journal = JournalFixtures.buildJournal({
      getAttentionResults: [unavailableFicusAttentionResult],
      getPlantsResults: [
        { kind: "read", plants: [currentPlant] },
        { kind: "read", plants: [freshPlant] },
      ],
      getOperationsByPlantId: {
        p1: [JournalFixtures.operationsPage([existing]), JournalFixtures.operationsPage([updated])],
      },
      editOperationResult: { kind: "edited", operation: updated },
      edited,
    });

    Testing.render(() => <App journal={journal} substrates={journal} pesticideCatalog={journal} />);
    await Testing.screen.findByText("3rd of March");

    const trigger = Testing.screen.getByRole("button", {
      name: "Edit recent repot operation 1 from 3rd of March",
    });
    trigger.focus();
    Testing.fireEvent.click(trigger);
    const editor = Testing.screen.getByRole("dialog", { name: "Operation editor" });
    Vitest.expect(editor).toHaveClass("sheet--entering");
    Vitest.expect(Testing.screen.getByRole("combobox", { name: "Operation type" })).toBeDisabled();
    Testing.fireEvent.input(Testing.screen.getByRole("spinbutton", { name: "Component 1 share" }), {
      target: { value: "80" },
    });
    Testing.fireEvent.input(Testing.screen.getByRole("textbox", { name: "Notes" }), {
      target: { value: "Less perlite" },
    });
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Save operation" }));

    const expectedOperation: Journal.OperationDetails = {
      kind: "repot",
      substrate: Journal.substrate([{ component: perliteId, share: Journal.percentage(80) }]),
      maybeNote: Journal.note("Less perlite"),
    };
    await Testing.waitFor(() => {
      Vitest.expect(edited).toEqual([{ operationId: "o1", details: expectedOperation }]);
    });
    Vitest.expect(await Testing.screen.findByText("Less perlite")).toBeInTheDocument();
    const card = Testing.screen.getByRole("article", { name: "Fern" });
    Vitest.expect(card).toHaveTextContent("Perlite 80%");
    await Testing.waitFor(() => {
      const editControl = Testing.screen.getByRole("button", {
        name: "Edit recent repot operation 1 from 3rd of March",
      });
      Vitest.expect(editControl).toHaveFocus();
    });
  });

  Vitest.it("should show fresh substrate after a repot despite stale attention", async () => {
    const currentPlant = JournalFixtures.ficus();
    const freshPlant = {
      ...currentPlant,
      details: {
        ...currentPlant.details,
        substrate: Journal.substrate([{ component: perliteId, share: Journal.percentage(80) }]),
      },
    };
    const journal = JournalFixtures.buildJournal({
      getPlantsResults: [
        { kind: "read", plants: [currentPlant] },
        { kind: "read", plants: [freshPlant] },
      ],
      getAttentionResults: [unavailableFicusAttentionResult],
      getOperationsByPlantId: {
        p1: [
          JournalFixtures.operationsPage(),
          JournalFixtures.operationsPage([JournalFixtures.repot("new", "2026-09-23T09:00:00Z")]),
        ],
      },
      logOperationResult: { kind: "logged", id: Journal.operationId("new") },
    });

    Testing.render(() => <App journal={journal} substrates={journal} pesticideCatalog={journal} />);
    const card = await Testing.screen.findByRole("article", { name: "Fern" });
    Vitest.expect(Testing.within(card).getByText("Perlite 100%")).toBeInTheDocument();

    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Log operation for Fern" }));
    Testing.fireEvent.change(Testing.screen.getByRole("combobox", { name: "Operation type" }), {
      target: { value: "repot" },
    });
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Save operation" }));

    await Testing.waitFor(() => {
      Vitest.expect(Testing.within(card).getByText("Perlite 80%")).toBeInTheDocument();
    });
  });

  Vitest.it("should preserve the current substrate after logging an older repot", async () => {
    const currentPlant = JournalFixtures.ficus();
    const journal = JournalFixtures.buildJournal({
      getPlantsResults: [
        { kind: "read", plants: [currentPlant] },
        { kind: "read", plants: [currentPlant] },
      ],
      getAttentionResults: [unavailableFicusAttentionResult],
      getOperationsByPlantId: {
        p1: [
          JournalFixtures.operationsPage(),
          JournalFixtures.operationsPage([JournalFixtures.repot("old", "2026-01-01T09:00:00Z")]),
        ],
      },
      logOperationResult: { kind: "logged", id: Journal.operationId("old") },
    });
    Testing.render(() => <App journal={journal} substrates={journal} pesticideCatalog={journal} />);
    await Testing.screen.findByRole("article", { name: "Fern" });

    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Log operation for Fern" }));
    Testing.fireEvent.change(Testing.screen.getByRole("combobox", { name: "Operation type" }), {
      target: { value: "repot" },
    });
    Testing.fireEvent.input(Testing.screen.getByLabelText("Date and time"), {
      target: { value: "2026-01-01T09:00" },
    });
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Save operation" }));

    const card = await Testing.screen.findByRole("article", { name: "Fern" });
    await Testing.screen.findByText("1st of January");
    Vitest.expect(Testing.within(card).getAllByText("Perlite 100%")).toHaveLength(2);
  });

  Vitest.it("should close the operation editor with Escape", async () => {
    const journal = JournalFixtures.buildJournal({
      getAttentionResults: [unavailableFicusAttentionResult],
      getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
    });
    const { container } = Testing.render(() => (
      <App journal={journal} substrates={journal} pesticideCatalog={journal} />
    ));
    const current = Testing.within(container);
    await current.findByRole("article", { name: "Fern" });
    const header = current.getByRole("heading", { name: "Plant Journal" }).closest("header");
    const journalRows = current.getByRole("region", { name: "Garden" });

    const trigger = current.getByRole("button", { name: "Log operation for Fern" });
    trigger.focus();
    Testing.fireEvent.click(trigger);
    const dialog = current.getByRole("dialog", { name: "Operation editor" });
    Vitest.expect(document.activeElement).toBe(dialog);
    Vitest.expect(header).toHaveProperty("inert", true);
    Vitest.expect(journalRows).toHaveProperty("inert", true);
    Testing.fireEvent.keyDown(window, { key: "Enter" });
    Vitest.expect(current.getByRole("dialog", { name: "Operation editor" })).toBeInTheDocument();
    Testing.fireEvent.keyDown(window, { key: "Escape" });

    Vitest.expect(dialog).toHaveClass("sheet--closing");
    await Testing.waitFor(() => {
      Vitest.expect(current.queryByRole("dialog")).not.toBeInTheDocument();
      Vitest.expect(header).toHaveProperty("inert", false);
      Vitest.expect(journalRows).toHaveProperty("inert", false);
      Vitest.expect(trigger).toHaveFocus();
    });
  });

  Vitest.it("should add and edit substrate components from the repot form", async () => {
    const pumiceId = Journal.substrateComponentId("00000000-0000-4000-8000-000000000005");
    const addedComponents: Journal.SubstrateComponentData[] = [];
    const editedComponents: {
      id: ReturnType<typeof Journal.substrateComponentId>;
      data: Journal.SubstrateComponentData;
    }[] = [];
    const addedComponent = { name: Journal.nomenclatureName("Pumice"), maybeInfo: null };
    const editedComponent = { name: Journal.nomenclatureName("Fine perlite"), maybeInfo: null };
    const journal = JournalFixtures.buildJournal({
      getAttentionResults: [unavailableFicusAttentionResult],
      getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
      componentAddResult: { kind: "added", entry: { id: pumiceId, data: addedComponent } },
      componentEditResult: { kind: "edited", entry: { id: perliteId, data: editedComponent } },
      addedComponents,
      editedComponents,
    });
    Testing.render(() => <App journal={journal} substrates={journal} pesticideCatalog={journal} />);
    await Testing.screen.findByRole("article", { name: "Fern" });

    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Log operation for Fern" }));
    const operationEditor = Testing.screen.getByRole("dialog", { name: "Operation editor" });
    Vitest.expect(operationEditor).toHaveClass("sheet--entering");
    Testing.fireEvent.change(Testing.screen.getByRole("combobox", { name: "Operation type" }), {
      target: { value: "repot" },
    });
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Edit Perlite" }));
    const componentEditor = Testing.screen.getByRole("dialog", {
      name: "Substrate component editor",
    });
    Vitest.expect(componentEditor).toBeInTheDocument();
    Testing.fireEvent.input(Testing.screen.getByRole("textbox", { name: "Name" }), {
      target: { value: "Fine perlite" },
    });
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Save" }));
    await Testing.waitForElementToBeRemoved(() =>
      Testing.screen.queryByRole("dialog", { name: "Substrate component editor" }),
    );
    Vitest.expect(Testing.screen.getByText("Fine perlite 100%")).toBeInTheDocument();

    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Define new component" }));
    Testing.fireEvent.input(Testing.screen.getByRole("textbox", { name: "Name" }), {
      target: { value: "Pumice" },
    });
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Save" }));
    const componentSelect = Testing.screen.getByRole("combobox", { name: "Component 1" });
    await Testing.waitFor(() => Vitest.expect(componentSelect).toHaveTextContent("Pumice"));
    await Testing.waitFor(() => {
      const editor = Testing.screen.queryByRole("dialog", { name: "Substrate component editor" });
      Vitest.expect(editor).toBeNull();
    });
    Vitest.expect(addedComponents).toEqual([addedComponent]);
    Vitest.expect(editedComponents).toEqual([{ id: perliteId, data: editedComponent }]);
  });

  Vitest.it("should add and edit pesticides from the care form", async () => {
    const neemId = Journal.pesticideId("00000000-0000-4000-8001-000000000003");
    const soapId = Journal.pesticideId("00000000-0000-4000-8001-000000000004");
    const addedPesticides: Journal.PesticideData[] = [];
    const editedPesticides: {
      id: ReturnType<typeof Journal.pesticideId>;
      data: Journal.PesticideData;
    }[] = [];
    const pesticides = [
      {
        id: neemId,
        data: {
          name: Journal.nomenclatureName("Neem oil"),
          pesticideType: "insecticide" as const,
          maybeInfo: null,
        },
      },
      {
        id: Journal.pesticideId("00000000-0000-4000-8001-000000000006"),
        data: {
          name: Journal.nomenclatureName("Spinosad"),
          pesticideType: "insecticide" as const,
          maybeInfo: null,
        },
      },
    ];
    const addedPesticide: Journal.PesticideData = {
      name: Journal.nomenclatureName("Insecticidal soap"),
      pesticideType: "insecticide",
      maybeInfo: null,
    };
    const editedPesticide: Journal.PesticideData = {
      name: Journal.nomenclatureName("Neem concentrate"),
      pesticideType: "treatment",
      maybeInfo: Journal.nomenclatureInfo("Dilute first"),
    };
    const existingOperation = JournalFixtures.care({
      id: "o1",
      date: "2026-03-03T00:00:00Z",
      moisture: "wet",
      actions: new Set(["pesticide"]),
      pesticides: new Set([neemId]),
    });
    const journal = JournalFixtures.buildJournal({
      getAttentionResults: [unavailableFicusAttentionResult],
      getOperationsByPlantId: { p1: [JournalFixtures.operationsPage([existingOperation])] },
      getPesticidesResult: { kind: "read", entries: pesticides },
      pesticideAddResult: { kind: "added", entry: { id: soapId, data: addedPesticide } },
      pesticideEditResult: { kind: "edited", entry: { id: neemId, data: editedPesticide } },
      addedPesticides,
      editedPesticides,
    });
    Testing.render(() => <App journal={journal} substrates={journal} pesticideCatalog={journal} />);
    await Testing.screen.findByRole("article", { name: "Fern" });

    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Log operation for Fern" }));
    const operation = Testing.screen.getByRole("dialog", { name: "Operation editor" });
    Testing.fireEvent.input(Testing.within(operation).getByRole("textbox", { name: "Notes" }), {
      target: { value: "Draft treatment notes" },
    });
    Testing.fireEvent.click(Testing.screen.getByRole("checkbox", { name: "Pesticide" }));
    Vitest.expect(Testing.screen.getAllByLabelText("Insecticide")).toHaveLength(2);
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Edit Neem oil" }));
    Vitest.expect(operation.querySelector(".operation-form__body")).toHaveProperty("inert", true);
    Testing.fireEvent.input(Testing.screen.getByRole("textbox", { name: "Name" }), {
      target: { value: "Neem concentrate" },
    });
    Testing.fireEvent.change(Testing.screen.getByRole("combobox", { name: "Type" }), {
      target: { value: "treatment" },
    });
    Testing.fireEvent.input(Testing.screen.getByRole("textbox", { name: "Info" }), {
      target: { value: "Dilute first" },
    });
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Save" }));
    await Testing.waitFor(() => {
      const editor = Testing.screen.queryByRole("dialog", { name: "Pesticide editor" });
      Vitest.expect(editor).toBeNull();
    });
    Vitest.expect(operation.querySelector(".operation-form__body")).toHaveProperty("inert", false);
    const editNeem = Testing.screen.getByRole("button", { name: "Edit Neem concentrate" });
    const neemDetails = Testing.screen.getByText("Neem concentrate", { selector: "dd" });
    Vitest.expect(editNeem).toBeInTheDocument();
    Vitest.expect(neemDetails).toBeInTheDocument();

    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Define new pesticide" }));
    Testing.fireEvent.input(Testing.screen.getByRole("textbox", { name: "Name" }), {
      target: { value: "Insecticidal soap" },
    });
    Testing.fireEvent.change(Testing.screen.getByRole("combobox", { name: "Type" }), {
      target: { value: "insecticide" },
    });
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Save" }));
    const addedSoap = await Testing.screen.findByRole("checkbox", { name: "Insecticidal soap" });
    Vitest.expect(addedSoap).toBeInTheDocument();
    await Testing.waitFor(() => {
      const editor = Testing.screen.queryByRole("dialog", { name: "Pesticide editor" });
      Vitest.expect(editor).toBeNull();
    });
    Vitest.expect(addedPesticides).toEqual([addedPesticide]);
    Vitest.expect(editedPesticides).toEqual([{ id: neemId, data: editedPesticide }]);
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Edit Neem concentrate" }));
    Testing.fireEvent.keyDown(window, { key: "Escape" });
    Vitest.expect(operation.parentElement).not.toHaveClass("sheet-layer--editing");
    const closingEditor = Testing.screen.getByRole("dialog", { name: "Pesticide editor" });
    Vitest.expect(closingEditor).toHaveClass("sheet--closing");
    await Testing.waitFor(() => {
      const editor = Testing.screen.queryByRole("dialog", { name: "Pesticide editor" });
      const editControl = Testing.screen.getByRole("button", { name: "Edit Neem concentrate" });
      Vitest.expect(editor).toBeNull();
      Vitest.expect(editControl).toHaveFocus();
    });
    Vitest.expect(Testing.within(operation).getByRole("textbox", { name: "Notes" })).toHaveValue(
      "Draft treatment notes",
    );
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Edit Neem concentrate" }));
    const editor = Testing.screen.getByRole("dialog", { name: "Pesticide editor" });
    const collapseOperation = Testing.screen.getByRole("button", {
      name: "Collapse operation editor",
    });
    Testing.fireEvent.click(collapseOperation);
    Testing.fireEvent.click(collapseOperation);
    Vitest.expect(editor).toHaveClass("sheet--closing");
    Vitest.expect(operation).not.toHaveClass("sheet--closing");
    Vitest.expect(operation.parentElement).not.toHaveClass("sheet-layer--editing");
    await Testing.waitFor(() => {
      const remainingEditor = Testing.screen.queryByRole("dialog", { name: "Pesticide editor" });
      Vitest.expect(remainingEditor).toBeNull();
      Vitest.expect(operation).toHaveClass("sheet--closing");
    });
    await Testing.waitFor(() => {
      Vitest.expect(Testing.screen.queryByRole("dialog")).not.toBeInTheDocument();
    });
  });

  Vitest.it("should reorder plant cards when logging an operation changes attention", async () => {
    const ficusPlant = JournalFixtures.ficus();
    const monsteraPlant = JournalFixtures.monstera();
    const ficusUrgentAttention: Journal.AttentionSample = {
      plantId: ficusPlant.id,
      watering: {
        kind: "redAlert",
        sampleCount: 5,
        averageInterval: Journal.milliseconds("86400000"),
        elapsed: Journal.milliseconds("176400000"),
      },
    };
    const monsteraCurrentAttention: Journal.AttentionSample = {
      plantId: monsteraPlant.id,
      watering: {
        kind: "current",
        sampleCount: 5,
        averageInterval: Journal.milliseconds("86400000"),
        elapsed: Journal.milliseconds("43200000"),
      },
    };
    const initialAttentionResult: Journal.GetAttentionResult = {
      kind: "read",
      projection: {
        measuredAt: Journal.instant("2026-01-01T00:00:00Z"),
        plants: [ficusUrgentAttention, monsteraCurrentAttention],
      },
    };
    const ficusCurrentAttention: Journal.AttentionSample = {
      plantId: ficusPlant.id,
      watering: {
        kind: "current",
        sampleCount: 5,
        averageInterval: Journal.milliseconds("86400000"),
        elapsed: Journal.milliseconds("43200000"),
      },
    };
    const monsteraUrgentAttention: Journal.AttentionSample = {
      plantId: monsteraPlant.id,
      watering: {
        kind: "redAlert",
        sampleCount: 5,
        averageInterval: Journal.milliseconds("86400000"),
        elapsed: Journal.milliseconds("176400000"),
      },
    };
    const reorderedAttentionResult: Journal.GetAttentionResult = {
      kind: "read",
      projection: {
        measuredAt: Journal.instant("2026-01-02T00:00:00Z"),
        plants: [monsteraUrgentAttention, ficusCurrentAttention],
      },
    };
    const journal = JournalFixtures.buildJournal({
      getAttentionResults: [initialAttentionResult, reorderedAttentionResult],
      getOperationsByPlantId: {
        p1: [
          JournalFixtures.operationsPage(),
          JournalFixtures.operationsPage([
            JournalFixtures.care({ id: "new", date: "2026-05-05T00:00:00Z", moisture: "wet" }),
          ]),
        ],
        p2: [JournalFixtures.operationsPage(), JournalFixtures.operationsPage()],
      },
      logOperationResult: { kind: "logged", id: Journal.operationId("new") },
    });
    Testing.render(() => <App journal={journal} substrates={journal} pesticideCatalog={journal} />);

    const initialArticles = await Testing.screen.findAllByRole("article");
    Vitest.expect(initialArticles.map((article) => article.getAttribute("aria-label"))).toEqual([
      "Fern",
      "Monstera deliciosa",
    ]);

    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Log operation for Fern" }));
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Save operation" }));

    await Testing.waitFor(() => {
      const updatedArticles = Testing.screen.getAllByRole("article");
      Vitest.expect(updatedArticles.map((article) => article.getAttribute("aria-label"))).toEqual([
        "Monstera deliciosa",
        "Fern",
      ]);
    });
  });
});
