import { fireEvent, render, screen, waitFor, within } from "@solidjs/testing-library";
import { describe, expect, it } from "vitest";
import { OperationForm } from "../../src/app/OperationForm";
import type { OperationDetails, Pesticide, SubstrateComponent } from "../../src/domain/Journal";
import {
  nomenclatureInfo,
  nomenclatureName,
  percentage,
  pesticideId,
  substrate,
  substrateComponentId,
} from "../../src/domain/Journal";
import { care, repot } from "./JournalTestSupport";

const perliteId = substrateComponentId("00000000-0000-4000-8000-000000000003");
const pineBarkId = substrateComponentId("00000000-0000-4000-8000-000000000004");
const substrateComponents: readonly SubstrateComponent[] = [
  {
    id: perliteId,
    data: {
      name: nomenclatureName("Perlite"),
      maybeInfo: nomenclatureInfo("Improves drainage.\nUse up to 30%."),
    },
  },
  { id: pineBarkId, data: { name: nomenclatureName("Pine bark"), maybeInfo: null } },
];
const neemId = pesticideId("00000000-0000-4000-8001-000000000003");
const soapId = pesticideId("00000000-0000-4000-8001-000000000004");
const pesticides: readonly Pesticide[] = [
  {
    id: neemId,
    data: {
      name: nomenclatureName("Neem oil"),
      pesticideType: "insecticide",
      maybeInfo: nomenclatureInfo("Dilute before use.\nApply weekly."),
    },
  },
  {
    id: soapId,
    data: {
      name: nomenclatureName("Insecticidal soap"),
      pesticideType: "insecticide",
      maybeInfo: null,
    },
  },
];

describe("OperationForm", () => {
  it("should preserve care details, omit None, and allow collapsing", () => {
    let cancelled = false;
    render(() => (
      <OperationForm
        initial={care("o1", "2026-01-01T00:00:00Z", "wet").details}
        substrateComponents={substrateComponents}
        pesticides={pesticides}
        onAddSubstrateComponent={() => undefined}
        onEditSubstrateComponent={() => undefined}
        onAddPesticide={() => undefined}
        onEditPesticide={() => undefined}
        onSubmit={() => Promise.resolve()}
        onCancel={() => {
          cancelled = true;
        }}
      />
    ));

    expect(screen.getByRole("heading", { name: "Edit operation" })).toBeInTheDocument();
    expect(screen.queryByText("Amend entry")).not.toBeInTheDocument();
    expect(
      screen.queryByText("Record what changed while the details are still fresh."),
    ).not.toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "Operation type" })).toBeDisabled();
    expect(screen.getByRole("combobox", { name: "Moisture" })).toHaveValue("wet");
    expect(screen.queryByRole("checkbox", { name: "None" })).not.toBeInTheDocument();
    const actionChoices = within(screen.getByRole("group", { name: "Care actions" }))
      .getAllByRole("checkbox")
      .map((choice) => choice.parentElement?.textContent);
    expect(actionChoices).toEqual(["Watered", "Fertilized", "Pruned", "Pesticide"]);

    const watered = screen.getByRole("checkbox", { name: "Watered" });
    expect(watered).toBeChecked();
    fireEvent.click(watered);
    expect(watered).not.toBeChecked();
    fireEvent.click(screen.getByRole("button", { name: "Collapse operation editor" }));
    expect(cancelled).toBe(true);
  });

  it("should require distinct substrate components with shares totaling at most 100", () => {
    const submitted: OperationDetails[] = [];
    render(() => (
      <OperationForm
        initial={repot("o1", "2026-01-01T00:00:00Z").details}
        substrateComponents={substrateComponents}
        pesticides={pesticides}
        onAddSubstrateComponent={() => undefined}
        onEditSubstrateComponent={() => undefined}
        onAddPesticide={() => undefined}
        onEditPesticide={() => undefined}
        onSubmit={(details) => {
          submitted.push(details);
          return Promise.resolve();
        }}
        onCancel={() => undefined}
      />
    ));

    expect(screen.getByRole("tooltip", { name: /Improves drainage/ })).toHaveTextContent(
      "Improves drainage. Use up to 30%.",
    );
    const substrateInfo = screen.getByRole("button", { name: "Information about Perlite" });
    substrateInfo.focus();
    fireEvent.keyDown(substrateInfo, { key: "Enter" });
    expect(substrateInfo).toHaveFocus();
    fireEvent.keyDown(substrateInfo, { key: "Escape" });
    expect(substrateInfo).not.toHaveFocus();
    const share = screen.getByRole("spinbutton", { name: "Component 1 share" });
    share.focus();
    fireEvent.input(share, { target: { value: "0" } });
    expect(document.activeElement).toBe(share);
    fireEvent.submit(screen.getByRole("form", { name: "Edit operation" }));
    expect(screen.getByRole("alert")).toHaveTextContent(
      "Each substrate share must be a whole number from 1 to 100%.",
    );

    fireEvent.input(share, { target: { value: "100" } });
    expect(screen.queryByText("Component", { exact: true })).not.toBeInTheDocument();
    expect(screen.queryByText("Share", { exact: true })).not.toBeInTheDocument();
    expect(screen.getByText("%")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Extend mix" }));
    expect(screen.getByRole("combobox", { name: "Component 2" })).toHaveValue(pineBarkId);
    fireEvent.change(screen.getByRole("combobox", { name: "Component 2" }), {
      target: { value: perliteId },
    });
    const perliteInfoControls = screen.getAllByRole("button", {
      name: "Information about Perlite",
    });
    expect(perliteInfoControls[0]).not.toHaveAttribute(
      "aria-describedby",
      perliteInfoControls[1]?.getAttribute("aria-describedby"),
    );
    fireEvent.click(screen.getByRole("button", { name: "Save operation" }));
    expect(screen.getByRole("alert")).toHaveTextContent(
      "Each substrate component can only be used once.",
    );

    fireEvent.change(screen.getByRole("combobox", { name: "Component 2" }), {
      target: { value: pineBarkId },
    });
    fireEvent.click(screen.getByRole("button", { name: "Save operation" }));
    expect(screen.getByRole("alert")).toHaveTextContent(
      "Substrate shares cannot total more than 100%.",
    );

    fireEvent.input(share, { target: { value: "80" } });
    fireEvent.click(screen.getByRole("button", { name: "Remove component 2" }));
    fireEvent.click(screen.getByRole("button", { name: "Extend mix" }));
    fireEvent.click(screen.getByRole("button", { name: "Save operation" }));
    expect(submitted).toEqual([
      {
        kind: "repot",
        substrate: substrate([
          { component: perliteId, share: percentage(80) },
          { component: pineBarkId, share: percentage(1) },
        ]),
        maybeNote: null,
      },
    ]);
  });

  it("should reveal pesticide choices last and clear them when deselected", async () => {
    const submitted: OperationDetails[] = [];
    let addRequests = 0;
    const editRequests: Pesticide[] = [];
    render(() => (
      <OperationForm
        initial={undefined}
        substrateComponents={substrateComponents}
        pesticides={pesticides}
        onAddSubstrateComponent={() => undefined}
        onEditSubstrateComponent={() => undefined}
        onAddPesticide={() => {
          addRequests += 1;
        }}
        onEditPesticide={(pesticide) => {
          editRequests.push(pesticide);
        }}
        onSubmit={(details) => {
          submitted.push(details);
          return Promise.resolve();
        }}
        onCancel={() => undefined}
      />
    ));

    expect(screen.queryByRole("checkbox", { name: "Neem oil" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("checkbox", { name: "Pesticide" }));
    expect(screen.getByRole("tooltip", { name: /Dilute before use/ })).toHaveTextContent(
      "Dilute before use. Apply weekly.",
    );
    expect(
      screen
        .getByRole("combobox", { name: "Moisture" })
        .compareDocumentPosition(screen.getByRole("checkbox", { name: "Neem oil" })) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(screen.getAllByLabelText("Insecticide")).toHaveLength(2);
    const pesticideInfo = screen.getByRole("button", { name: "Information about Neem oil" });
    pesticideInfo.focus();
    fireEvent.keyDown(pesticideInfo, { key: "Enter" });
    expect(pesticideInfo).toHaveFocus();
    fireEvent.keyDown(pesticideInfo, { key: "Escape" });
    expect(pesticideInfo).not.toHaveFocus();
    fireEvent.click(screen.getByRole("checkbox", { name: "Neem oil" }));
    fireEvent.click(screen.getByRole("checkbox", { name: "Insecticidal soap" }));
    fireEvent.click(screen.getByRole("checkbox", { name: "Neem oil" }));
    fireEvent.click(screen.getByRole("checkbox", { name: "Neem oil" }));
    fireEvent.click(screen.getByRole("button", { name: "Edit Neem oil" }));
    expect(editRequests).toEqual([pesticides[0]]);
    fireEvent.click(screen.getByRole("button", { name: "Define new pesticide" }));
    expect(addRequests).toBe(1);
    fireEvent.click(screen.getByRole("checkbox", { name: "Pesticide" }));
    expect(screen.queryByRole("checkbox", { name: "Neem oil" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("checkbox", { name: "Pesticide" }));
    expect(screen.getByRole("checkbox", { name: "Neem oil" })).not.toBeChecked();
    fireEvent.click(screen.getByRole("checkbox", { name: "Neem oil" }));
    fireEvent.click(screen.getByRole("checkbox", { name: "Insecticidal soap" }));
    fireEvent.click(screen.getByRole("button", { name: "Save operation" }));

    await waitFor(() => {
      expect(submitted).toEqual([
        {
          kind: "care",
          actions: new Set(["pesticide"]),
          pesticides: new Set([neemId, soapId]),
          moisture: "noReading",
          maybeNote: null,
        },
      ]);
    });
  });

  it("should treat a persisted None action as no selected action", async () => {
    const submitted: OperationDetails[] = [];
    render(() => (
      <OperationForm
        initial={{
          kind: "care",
          actions: new Set(["noAction"]),
          pesticides: new Set(),
          moisture: "noReading",
          maybeNote: null,
        }}
        substrateComponents={substrateComponents}
        pesticides={pesticides}
        onAddSubstrateComponent={() => undefined}
        onEditSubstrateComponent={() => undefined}
        onAddPesticide={() => undefined}
        onEditPesticide={() => undefined}
        onSubmit={(details) => {
          submitted.push(details);
          return Promise.resolve();
        }}
        onCancel={() => undefined}
      />
    ));

    fireEvent.click(screen.getByRole("button", { name: "Save operation" }));
    await waitFor(() => {
      expect(submitted[0]).toMatchObject({ actions: new Set() });
    });
  });

  it("should open substrate creation and reject a repot with no components", () => {
    let addRequests = 0;
    render(() => (
      <OperationForm
        initial={undefined}
        substrateComponents={[]}
        pesticides={[]}
        onAddSubstrateComponent={() => {
          addRequests += 1;
        }}
        onEditSubstrateComponent={() => undefined}
        onAddPesticide={() => undefined}
        onEditPesticide={() => undefined}
        onSubmit={() => Promise.resolve()}
        onCancel={() => undefined}
      />
    ));

    fireEvent.change(screen.getByRole("combobox", { name: "Operation type" }), {
      target: { value: "repot" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Define new component" }));
    expect(addRequests).toBe(1);
    fireEvent.click(screen.getByRole("button", { name: "Save operation" }));
    expect(screen.getByRole("alert")).toHaveTextContent("Add at least one substrate component.");
  });

  it("should prevent another save while a save is in progress", async () => {
    let submissions = 0;
    let finishSaving: () => void = () => undefined;
    const saving = new Promise<void>((resolve) => {
      finishSaving = resolve;
    });
    render(() => (
      <OperationForm
        initial={undefined}
        substrateComponents={substrateComponents}
        pesticides={pesticides}
        onAddSubstrateComponent={() => undefined}
        onEditSubstrateComponent={() => undefined}
        onAddPesticide={() => undefined}
        onEditPesticide={() => undefined}
        onSubmit={() => {
          submissions += 1;
          return saving;
        }}
        onCancel={() => undefined}
      />
    ));

    fireEvent.click(screen.getByRole("button", { name: "Save operation" }));
    const savingButton = screen.getByRole("button", { name: "Saving…" });
    expect(savingButton).toBeDisabled();
    fireEvent.click(savingButton);
    expect(submissions).toBe(1);

    finishSaving();
    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Save operation" })).toBeEnabled();
    });
  });
});
