import * as Testing from "@solidjs/testing-library";
import * as Vitest from "vitest";
import { App } from "../../src/app/App";
import * as Journal from "../../src/domain/Journal";
import * as JournalFixtures from "./JournalTestSupport";

Vitest.afterEach(() => Reflect.deleteProperty(document, "startViewTransition"));

const perliteId = Journal.substrateComponentId("00000000-0000-4000-8000-000000000003");

Vitest.describe("changing the journal", () => {
  Vitest.it("should log care and refresh the plant's operation history", async () => {
    const startViewTransition = Vitest.vi.fn((update: () => void) => {
      update();
      return { updateCallbackDone: Promise.resolve() } as ViewTransition;
    });
    Object.defineProperty(document, "startViewTransition", {
      configurable: true,
      value: startViewTransition,
    });
    const logged: { plantId: string; details: Journal.OperationDetails }[] = [];
    const journal = JournalFixtures.buildJournal({
      getPlantsResult: { kind: "read", plants: [JournalFixtures.ficus()] },
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
    Testing.render(() => <App journal={journal} />);
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
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Save operation" }));

    await Testing.screen.findByText("2026-05-05");
    await Testing.waitFor(() => {
      Vitest.expect(
        Testing.screen.getByRole("button", { name: "Log operation for Fern" }),
      ).toHaveFocus();
    });
    const expectedOperation: Journal.OperationDetails = {
      kind: "care",
      actions: new Set(["watered"]),
      pesticides: new Set(),
      moisture: "wet",
      maybeNote: Journal.note("Recovered"),
    };
    Vitest.expect(startViewTransition).toHaveBeenCalledOnce();
    Vitest.expect(logged).toEqual([{ plantId: "p1", details: expectedOperation }]);
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
    const journal = JournalFixtures.buildJournal({
      getPlantsResult: { kind: "read", plants: [JournalFixtures.ficus()] },
      getOperationsByPlantId: {
        p1: [JournalFixtures.operationsPage([existing]), JournalFixtures.operationsPage([updated])],
      },
      editOperationResult: { kind: "edited", operation: updated },
      edited,
    });
    Testing.render(() => <App journal={journal} />);
    await Testing.screen.findByText("2026-03-03");

    const trigger = Testing.screen.getByRole("button", {
      name: "Edit repot operation 1 from 2026-03-03",
    });
    trigger.focus();
    Testing.fireEvent.click(trigger);
    Vitest.expect(Testing.screen.getByRole("dialog", { name: "Operation editor" })).toHaveClass(
      "sheet--entering",
    );
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
    await Testing.waitFor(() => {
      Vitest.expect(
        Testing.screen.getByRole("button", { name: "Edit repot operation 1 from 2026-03-03" }),
      ).toHaveFocus();
    });
  });

  Vitest.it("should close the operation editor with Escape", async () => {
    const journal = JournalFixtures.buildJournal({
      getPlantsResult: { kind: "read", plants: [JournalFixtures.ficus()] },
      getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
    });
    const { container } = Testing.render(() => <App journal={journal} />);
    const current = Testing.within(container);
    await current.findByRole("article", { name: "Fern" });
    const header = container.querySelector<HTMLElement>(".masthead")!;
    const journalRows = container.querySelector<HTMLElement>(".journal")!;

    const trigger = current.getByRole("button", { name: "Log operation for Fern" });
    trigger.focus();
    Testing.fireEvent.click(trigger);
    const dialog = current.getByRole("dialog", { name: "Operation editor" });
    Vitest.expect(document.activeElement).toBe(dialog);
    Vitest.expect(header.inert).toBe(true);
    Vitest.expect(journalRows.inert).toBe(true);
    Testing.fireEvent.keyDown(window, { key: "Enter" });
    Vitest.expect(current.getByRole("dialog", { name: "Operation editor" })).toBeInTheDocument();
    Testing.fireEvent.keyDown(window, { key: "Escape" });

    Vitest.expect(dialog).toHaveClass("sheet--closing");
    await Testing.waitFor(() => {
      Vitest.expect(current.queryByRole("dialog")).not.toBeInTheDocument();
      Vitest.expect(header.inert).toBe(false);
      Vitest.expect(journalRows.inert).toBe(false);
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
      getPlantsResult: { kind: "read", plants: [JournalFixtures.ficus()] },
      getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
      componentAddResult: { kind: "added", entry: { id: pumiceId, data: addedComponent } },
      componentEditResult: { kind: "edited", entry: { id: perliteId, data: editedComponent } },
      addedComponents,
      editedComponents,
    });
    Testing.render(() => <App journal={journal} />);
    await Testing.screen.findByRole("article", { name: "Fern" });

    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Log operation for Fern" }));
    Vitest.expect(Testing.screen.getByRole("dialog", { name: "Operation editor" })).toHaveClass(
      "sheet--entering",
    );
    Testing.fireEvent.change(Testing.screen.getByRole("combobox", { name: "Operation type" }), {
      target: { value: "repot" },
    });
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Edit Perlite" }));
    Vitest.expect(
      Testing.screen.getByRole("dialog", { name: "Substrate component editor" }),
    ).toBeInTheDocument();
    Testing.fireEvent.input(Testing.screen.getByRole("textbox", { name: "Name" }), {
      target: { value: "Fine perlite" },
    });
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Save" }));
    await Testing.waitFor(() =>
      Vitest.expect(
        Testing.screen.queryByRole("dialog", { name: "Substrate component editor" }),
      ).not.toBeInTheDocument(),
    );
    Vitest.expect(Testing.screen.getByText("Fine perlite 100%")).toBeInTheDocument();

    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Define new component" }));
    Testing.fireEvent.input(Testing.screen.getByRole("textbox", { name: "Name" }), {
      target: { value: "Pumice" },
    });
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Save" }));
    await Testing.waitFor(() =>
      Vitest.expect(
        Testing.screen.getByRole("combobox", { name: "Component 1" }),
      ).toHaveTextContent("Pumice"),
    );
    await Testing.waitFor(() => {
      Vitest.expect(
        Testing.screen.queryByRole("dialog", { name: "Substrate component editor" }),
      ).not.toBeInTheDocument();
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
      getPlantsResult: { kind: "read", plants: [JournalFixtures.ficus()] },
      getOperationsByPlantId: { p1: [JournalFixtures.operationsPage([existingOperation])] },
      getPesticidesResult: { kind: "read", entries: pesticides },
      pesticideAddResult: { kind: "added", entry: { id: soapId, data: addedPesticide } },
      pesticideEditResult: { kind: "edited", entry: { id: neemId, data: editedPesticide } },
      addedPesticides,
      editedPesticides,
    });
    Testing.render(() => <App journal={journal} />);
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
      Vitest.expect(
        Testing.screen.queryByRole("dialog", { name: "Pesticide editor" }),
      ).not.toBeInTheDocument();
    });
    Vitest.expect(operation.querySelector(".operation-form__body")).toHaveProperty("inert", false);
    Vitest.expect(
      Testing.screen.getByRole("button", { name: "Edit Neem concentrate" }),
    ).toBeInTheDocument();
    Vitest.expect(
      Testing.screen.getByText("Neem concentrate", { selector: "dd" }),
    ).toBeInTheDocument();

    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Define new pesticide" }));
    Testing.fireEvent.input(Testing.screen.getByRole("textbox", { name: "Name" }), {
      target: { value: "Insecticidal soap" },
    });
    Testing.fireEvent.change(Testing.screen.getByRole("combobox", { name: "Type" }), {
      target: { value: "insecticide" },
    });
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Save" }));
    Vitest.expect(
      await Testing.screen.findByRole("checkbox", { name: "Insecticidal soap" }),
    ).toBeInTheDocument();
    await Testing.waitFor(() => {
      Vitest.expect(
        Testing.screen.queryByRole("dialog", { name: "Pesticide editor" }),
      ).not.toBeInTheDocument();
    });
    Vitest.expect(addedPesticides).toEqual([addedPesticide]);
    Vitest.expect(editedPesticides).toEqual([{ id: neemId, data: editedPesticide }]);
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Edit Neem concentrate" }));
    Testing.fireEvent.keyDown(window, { key: "Escape" });
    Vitest.expect(operation.parentElement).not.toHaveClass("sheet-layer--editing");
    Vitest.expect(Testing.screen.getByRole("dialog", { name: "Pesticide editor" })).toHaveClass(
      "sheet--closing",
    );
    await Testing.waitFor(() => {
      Vitest.expect(
        Testing.screen.queryByRole("dialog", { name: "Pesticide editor" }),
      ).not.toBeInTheDocument();
      Vitest.expect(
        Testing.screen.getByRole("button", { name: "Edit Neem concentrate" }),
      ).toHaveFocus();
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
      Vitest.expect(
        Testing.screen.queryByRole("dialog", { name: "Pesticide editor" }),
      ).not.toBeInTheDocument();
      Vitest.expect(operation).toHaveClass("sheet--closing");
    });
    await Testing.waitFor(() => {
      Vitest.expect(Testing.screen.queryByRole("dialog")).not.toBeInTheDocument();
    });
  });
});
