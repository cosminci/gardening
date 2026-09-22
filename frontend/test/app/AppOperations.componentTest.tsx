import { fireEvent, render, screen, waitFor, within } from "@solidjs/testing-library";
import { afterEach, describe, expect, it, vi } from "vitest";
import { App } from "../../src/app/App";
import type {
  OperationDetails,
  PesticideData,
  SubstrateComponentData,
} from "../../src/domain/Journal";
import {
  nomenclatureInfo,
  nomenclatureName,
  note,
  operationId,
  percentage,
  pesticideId,
  substrate,
  substrateComponentId,
} from "../../src/domain/Journal";
import { buildJournal, care, ficus, repot } from "./JournalTestSupport";

afterEach(() => Reflect.deleteProperty(document, "startViewTransition"));

const perliteId = substrateComponentId("00000000-0000-4000-8000-000000000003");

describe("changing the journal", () => {
  it("should log care and refresh the plant's operation history", async () => {
    const startViewTransition = vi.fn((update: () => void) => {
      update();
      return { updateCallbackDone: Promise.resolve() } as ViewTransition;
    });
    Object.defineProperty(document, "startViewTransition", {
      configurable: true,
      value: startViewTransition,
    });
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
    expect(startViewTransition).toHaveBeenCalledOnce();
    expect(logged).toEqual([
      {
        plantId: "p1",
        details: {
          kind: "care",
          actions: new Set(["watered"]),
          pesticides: new Set(),
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
        substrate: substrate([{ component: perliteId, share: percentage(80) }]),
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
    expect(screen.getByRole("dialog", { name: "Operation editor" })).toHaveClass("sheet--entering");
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
            substrate: substrate([{ component: perliteId, share: percentage(80) }]),
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
    fireEvent.keyDown(window, { key: "Enter" });
    expect(current.getByRole("dialog", { name: "Operation editor" })).toBeInTheDocument();
    fireEvent.keyDown(window, { key: "Escape" });

    expect(dialog).toHaveClass("sheet--closing");
    await waitFor(() => {
      expect(current.queryByRole("dialog")).not.toBeInTheDocument();
      expect(header.inert).toBe(false);
      expect(journalRows.inert).toBe(false);
      expect(trigger).toHaveFocus();
    });
  });

  it("should add and edit substrate components from the repot form", async () => {
    const pumiceId = substrateComponentId("00000000-0000-4000-8000-000000000005");
    const addedComponents: SubstrateComponentData[] = [];
    const editedComponents: {
      id: ReturnType<typeof substrateComponentId>;
      data: SubstrateComponentData;
    }[] = [];
    const journal = buildJournal({
      getPlantsResult: { kind: "read", plants: [ficus()] },
      getOperationsByPlantId: { p1: [{ kind: "read", operations: [] }] },
      componentAddResult: {
        kind: "added",
        entry: {
          id: pumiceId,
          data: { name: nomenclatureName("Pumice"), maybeInfo: null },
        },
      },
      componentEditResult: {
        kind: "edited",
        entry: {
          id: perliteId,
          data: { name: nomenclatureName("Fine perlite"), maybeInfo: null },
        },
      },
      addedComponents,
      editedComponents,
    });
    render(() => <App journal={journal} />);
    await screen.findByRole("article", { name: "Fern" });

    fireEvent.click(screen.getByRole("button", { name: "Log operation for Fern" }));
    expect(screen.getByRole("dialog", { name: "Operation editor" })).toHaveClass("sheet--entering");
    fireEvent.change(screen.getByRole("combobox", { name: "Operation type" }), {
      target: { value: "repot" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Edit Perlite" }));
    expect(screen.getByRole("dialog", { name: "Substrate component editor" })).toBeInTheDocument();
    fireEvent.input(screen.getByRole("textbox", { name: "Name" }), {
      target: { value: "Fine perlite" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() =>
      expect(
        screen.queryByRole("dialog", { name: "Substrate component editor" }),
      ).not.toBeInTheDocument(),
    );
    expect(screen.getByText("Fine perlite 100%")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Define new component" }));
    fireEvent.input(screen.getByRole("textbox", { name: "Name" }), {
      target: { value: "Pumice" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() =>
      expect(screen.getByRole("combobox", { name: "Component 1" })).toHaveTextContent("Pumice"),
    );
    await waitFor(() => {
      expect(
        screen.queryByRole("dialog", { name: "Substrate component editor" }),
      ).not.toBeInTheDocument();
    });
    expect(addedComponents).toEqual([{ name: nomenclatureName("Pumice"), maybeInfo: null }]);
    expect(editedComponents).toEqual([
      {
        id: perliteId,
        data: { name: nomenclatureName("Fine perlite"), maybeInfo: null },
      },
    ]);
  });

  it("should add and edit pesticides from the care form", async () => {
    const neemId = pesticideId("00000000-0000-4000-8001-000000000003");
    const soapId = pesticideId("00000000-0000-4000-8001-000000000004");
    const addedPesticides: PesticideData[] = [];
    const editedPesticides: {
      id: ReturnType<typeof pesticideId>;
      data: PesticideData;
    }[] = [];
    const journal = buildJournal({
      getPlantsResult: { kind: "read", plants: [ficus()] },
      getOperationsByPlantId: {
        p1: [
          {
            kind: "read",
            operations: [
              care(
                "o1",
                "2026-03-03T00:00:00Z",
                "wet",
                null,
                new Set(["pesticide"]),
                new Set([neemId]),
              ),
            ],
          },
        ],
      },
      getPesticidesResult: {
        kind: "read",
        entries: [
          {
            id: neemId,
            data: {
              name: nomenclatureName("Neem oil"),
              pesticideType: "insecticide",
              maybeInfo: null,
            },
          },
          {
            id: pesticideId("00000000-0000-4000-8001-000000000006"),
            data: {
              name: nomenclatureName("Spinosad"),
              pesticideType: "insecticide",
              maybeInfo: null,
            },
          },
        ],
      },
      pesticideAddResult: {
        kind: "added",
        entry: {
          id: soapId,
          data: {
            name: nomenclatureName("Insecticidal soap"),
            pesticideType: "insecticide",
            maybeInfo: null,
          },
        },
      },
      pesticideEditResult: {
        kind: "edited",
        entry: {
          id: neemId,
          data: {
            name: nomenclatureName("Neem concentrate"),
            pesticideType: "treatment",
            maybeInfo: nomenclatureInfo("Dilute first"),
          },
        },
      },
      addedPesticides,
      editedPesticides,
    });
    render(() => <App journal={journal} />);
    await screen.findByRole("article", { name: "Fern" });

    fireEvent.click(screen.getByRole("button", { name: "Log operation for Fern" }));
    const operation = screen.getByRole("dialog", { name: "Operation editor" });
    fireEvent.input(within(operation).getByRole("textbox", { name: "Notes" }), {
      target: { value: "Draft treatment notes" },
    });
    fireEvent.click(screen.getByRole("checkbox", { name: "Pesticide" }));
    expect(screen.getAllByLabelText("Insecticide")).toHaveLength(2);
    fireEvent.click(screen.getByRole("button", { name: "Edit Neem oil" }));
    expect(operation.querySelector(".operation-form__body")).toHaveProperty("inert", true);
    fireEvent.input(screen.getByRole("textbox", { name: "Name" }), {
      target: { value: "Neem concentrate" },
    });
    fireEvent.change(screen.getByRole("combobox", { name: "Type" }), {
      target: { value: "treatment" },
    });
    fireEvent.input(screen.getByRole("textbox", { name: "Info" }), {
      target: { value: "Dilute first" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => {
      expect(screen.queryByRole("dialog", { name: "Pesticide editor" })).not.toBeInTheDocument();
    });
    expect(operation.querySelector(".operation-form__body")).toHaveProperty("inert", false);
    expect(screen.getByRole("button", { name: "Edit Neem concentrate" })).toBeInTheDocument();
    expect(screen.getByText("Neem concentrate", { selector: "dd" })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Define new pesticide" }));
    fireEvent.input(screen.getByRole("textbox", { name: "Name" }), {
      target: { value: "Insecticidal soap" },
    });
    fireEvent.change(screen.getByRole("combobox", { name: "Type" }), {
      target: { value: "insecticide" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByRole("checkbox", { name: "Insecticidal soap" })).toBeInTheDocument();
    await waitFor(() => {
      expect(screen.queryByRole("dialog", { name: "Pesticide editor" })).not.toBeInTheDocument();
    });
    expect(addedPesticides).toEqual([
      {
        name: nomenclatureName("Insecticidal soap"),
        pesticideType: "insecticide",
        maybeInfo: null,
      },
    ]);
    expect(editedPesticides).toEqual([
      {
        id: neemId,
        data: {
          name: nomenclatureName("Neem concentrate"),
          pesticideType: "treatment",
          maybeInfo: nomenclatureInfo("Dilute first"),
        },
      },
    ]);
    fireEvent.click(screen.getByRole("button", { name: "Edit Neem concentrate" }));
    fireEvent.keyDown(window, { key: "Escape" });
    expect(operation.parentElement).not.toHaveClass("sheet-layer--editing");
    expect(screen.getByRole("dialog", { name: "Pesticide editor" })).toHaveClass("sheet--closing");
    await waitFor(() => {
      expect(screen.queryByRole("dialog", { name: "Pesticide editor" })).not.toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Edit Neem concentrate" })).toHaveFocus();
    });
    expect(within(operation).getByRole("textbox", { name: "Notes" })).toHaveValue(
      "Draft treatment notes",
    );
    fireEvent.click(screen.getByRole("button", { name: "Edit Neem concentrate" }));
    const editor = screen.getByRole("dialog", { name: "Pesticide editor" });
    const collapseOperation = screen.getByRole("button", { name: "Collapse operation editor" });
    fireEvent.click(collapseOperation);
    fireEvent.click(collapseOperation);
    expect(editor).toHaveClass("sheet--closing");
    expect(operation).not.toHaveClass("sheet--closing");
    expect(operation.parentElement).not.toHaveClass("sheet-layer--editing");
    await waitFor(() => {
      expect(screen.queryByRole("dialog", { name: "Pesticide editor" })).not.toBeInTheDocument();
      expect(operation).toHaveClass("sheet--closing");
    });
    await waitFor(() => {
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });
  });
});
