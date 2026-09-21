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
  pesticideType,
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
    fireEvent.keyDown(window, { key: "Escape" });

    expect(current.queryByRole("dialog")).not.toBeInTheDocument();
    expect(header.inert).toBe(false);
    expect(journalRows.inert).toBe(false);
    expect(trigger).toHaveFocus();
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
    fireEvent.change(screen.getByRole("combobox", { name: "Operation type" }), {
      target: { value: "repot" },
    });
    fireEvent.click(screen.getByText("Manage substrate components"));
    fireEvent.input(screen.getByRole("textbox", { name: "Name for Perlite" }), {
      target: { value: "Fine perlite" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Save Perlite" }));
    await screen.findByRole("button", { name: "Save Fine perlite" });
    expect(screen.getByText("Fine perlite 100%")).toBeInTheDocument();

    fireEvent.input(screen.getByRole("textbox", { name: "New substrate component name" }), {
      target: { value: "Pumice" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Add substrate component" }));
    await waitFor(() =>
      expect(screen.getByRole("combobox", { name: "Component 1" })).toHaveTextContent("Pumice"),
    );
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
              pesticideType: pesticideType("organic"),
              maybeInfo: null,
            },
          },
          {
            id: pesticideId("00000000-0000-4000-8001-000000000006"),
            data: {
              name: nomenclatureName("Spinosad"),
              pesticideType: pesticideType("organic"),
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
            pesticideType: pesticideType("soap"),
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
            pesticideType: pesticideType("botanical"),
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
    fireEvent.click(screen.getByRole("checkbox", { name: "Insecticide / H2O2" }));
    fireEvent.click(screen.getByText("Manage pesticides"));
    fireEvent.input(screen.getByRole("textbox", { name: "Name for Neem oil" }), {
      target: { value: "Neem concentrate" },
    });
    fireEvent.input(screen.getByRole("textbox", { name: "Type for Neem oil" }), {
      target: { value: "botanical" },
    });
    fireEvent.input(screen.getByRole("textbox", { name: "Info for Neem oil" }), {
      target: { value: "Dilute first" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Save Neem oil" }));
    await screen.findByRole("button", { name: "Save Neem concentrate" });
    expect(screen.getByText("Neem concentrate", { selector: "dd" })).toBeInTheDocument();

    fireEvent.input(screen.getByRole("textbox", { name: "New pesticide name" }), {
      target: { value: "Insecticidal soap" },
    });
    fireEvent.input(screen.getByRole("textbox", { name: "New pesticide type" }), {
      target: { value: "soap" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Add pesticide" }));
    expect(await screen.findByRole("checkbox", { name: "Insecticidal soap" })).toBeInTheDocument();
    expect(addedPesticides).toEqual([
      {
        name: nomenclatureName("Insecticidal soap"),
        pesticideType: pesticideType("soap"),
        maybeInfo: null,
      },
    ]);
    expect(editedPesticides).toEqual([
      {
        id: neemId,
        data: {
          name: nomenclatureName("Neem concentrate"),
          pesticideType: pesticideType("botanical"),
          maybeInfo: nomenclatureInfo("Dilute first"),
        },
      },
    ]);
  });
});
