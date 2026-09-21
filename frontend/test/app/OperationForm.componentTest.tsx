import { fireEvent, render, screen, waitFor } from "@solidjs/testing-library";
import { describe, expect, it, vi } from "vitest";
import { OperationForm } from "../../src/app/OperationForm";
import type { OperationDetails, Pesticide, SubstrateComponent } from "../../src/domain/Journal";
import {
  nomenclatureName,
  nomenclatureInfo,
  percentage,
  pesticideId,
  pesticideType,
  substrate,
  substrateComponentId,
} from "../../src/domain/Journal";
import { care, repot } from "./JournalTestSupport";

const perliteId = substrateComponentId("00000000-0000-4000-8000-000000000003");
const pineBarkId = substrateComponentId("00000000-0000-4000-8000-000000000004");
const substrateComponents: readonly SubstrateComponent[] = [
  { id: perliteId, data: { name: nomenclatureName("Perlite"), maybeInfo: null } },
  { id: pineBarkId, data: { name: nomenclatureName("Pine bark"), maybeInfo: null } },
];
const selectedPesticide = pesticideId("00000000-0000-4000-8001-000000000003");
const pesticides: readonly Pesticide[] = [
  {
    id: selectedPesticide,
    data: {
      name: nomenclatureName("Neem oil"),
      pesticideType: pesticideType("organic"),
      maybeInfo: null,
    },
  },
  {
    id: pesticideId("00000000-0000-4000-8001-000000000004"),
    data: {
      name: nomenclatureName("Insecticidal soap"),
      pesticideType: pesticideType("soap"),
      maybeInfo: null,
    },
  },
];
const failComponentAdd = () =>
  Promise.resolve({ kind: "addFailed", reason: new Error("unexpected write") } as const);
const failComponentEdit = () =>
  Promise.resolve({ kind: "editFailed", reason: new Error("unexpected write") } as const);
const failPesticideAdd = () =>
  Promise.resolve({ kind: "addFailed", reason: new Error("unexpected write") } as const);
const failPesticideEdit = () =>
  Promise.resolve({ kind: "editFailed", reason: new Error("unexpected write") } as const);

describe("OperationForm", () => {
  it("should preserve care details while editing and allow cancellation", () => {
    let cancelled = false;
    render(() => (
      <OperationForm
        initial={care("o1", "2026-01-01T00:00:00Z", "wet").details}
        substrateComponents={substrateComponents}
        pesticides={pesticides}
        onAddSubstrateComponent={failComponentAdd}
        onEditSubstrateComponent={failComponentEdit}
        onAddPesticide={failPesticideAdd}
        onEditPesticide={failPesticideEdit}
        onSubmit={() => Promise.resolve()}
        onCancel={() => {
          cancelled = true;
        }}
      />
    ));

    expect(screen.getByRole("combobox", { name: "Operation type" })).toBeDisabled();
    expect(screen.getByRole("combobox", { name: "Moisture" })).toHaveValue("wet");
    const watered = screen.getByRole("checkbox", { name: "Watered" });
    const noAction = screen.getByRole("checkbox", { name: "None" });
    expect(watered).toBeChecked();
    fireEvent.click(watered);
    expect(watered).not.toBeChecked();
    fireEvent.click(noAction);
    expect(noAction).toBeChecked();
    fireEvent.click(watered);
    expect(watered).toBeChecked();
    expect(noAction).not.toBeChecked();
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(cancelled).toBe(true);
  });

  it("should require distinct substrate components with shares totaling at most 100", () => {
    const submitted: OperationDetails[] = [];
    render(() => (
      <OperationForm
        initial={repot("o1", "2026-01-01T00:00:00Z").details}
        substrateComponents={substrateComponents}
        pesticides={pesticides}
        onAddSubstrateComponent={failComponentAdd}
        onEditSubstrateComponent={failComponentEdit}
        onAddPesticide={failPesticideAdd}
        onEditPesticide={failPesticideEdit}
        onSubmit={(details) => {
          submitted.push(details);
          return Promise.resolve();
        }}
        onCancel={() => undefined}
      />
    ));

    const share = screen.getByRole("spinbutton", { name: "Component 1 share" });
    share.focus();
    fireEvent.input(share, {
      target: { value: "0" },
    });
    expect(document.activeElement).toBe(share);
    fireEvent.submit(screen.getByRole("form", { name: "Edit operation" }));
    expect(screen.getByRole("alert")).toHaveTextContent(
      "Each substrate share must be a whole number from 1 to 100%.",
    );
    fireEvent.input(screen.getByRole("spinbutton", { name: "Component 1 share" }), {
      target: { value: "100" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Add component" }));
    expect(screen.getByRole("spinbutton", { name: "Component 2 share" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Remove component 2" })).toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "Component 2" })).toHaveValue(pineBarkId);
    fireEvent.change(screen.getByRole("combobox", { name: "Component 2" }), {
      target: { value: perliteId },
    });
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

    fireEvent.input(screen.getByRole("spinbutton", { name: "Component 1 share" }), {
      target: { value: "80" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Remove component 2" }));
    fireEvent.click(screen.getByRole("button", { name: "Add component" }));
    fireEvent.change(screen.getByRole("combobox", { name: "Component 2" }), {
      target: { value: pineBarkId },
    });
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

  it("should preserve pesticide references while editing care", async () => {
    const submitted: OperationDetails[] = [];
    render(() => (
      <OperationForm
        initial={{
          kind: "care",
          actions: new Set(["pesticide"]),
          pesticides: new Set([selectedPesticide]),
          moisture: "wet",
          maybeNote: null,
        }}
        substrateComponents={substrateComponents}
        pesticides={pesticides}
        onAddSubstrateComponent={failComponentAdd}
        onEditSubstrateComponent={failComponentEdit}
        onAddPesticide={failPesticideAdd}
        onEditPesticide={failPesticideEdit}
        onSubmit={(details) => {
          submitted.push(details);
          return Promise.resolve();
        }}
        onCancel={() => undefined}
      />
    ));

    fireEvent.click(screen.getByRole("button", { name: "Save operation" }));
    await waitFor(() => {
      expect(submitted).toEqual([
        {
          kind: "care",
          actions: new Set(["pesticide"]),
          pesticides: new Set([selectedPesticide]),
          moisture: "wet",
          maybeNote: null,
        },
      ]);
    });
  });

  it("should show pesticide choices only for pesticide care and submit multiple choices", async () => {
    const submitted: OperationDetails[] = [];
    render(() => (
      <OperationForm
        initial={undefined}
        substrateComponents={substrateComponents}
        pesticides={pesticides}
        onAddSubstrateComponent={failComponentAdd}
        onEditSubstrateComponent={failComponentEdit}
        onAddPesticide={failPesticideAdd}
        onEditPesticide={failPesticideEdit}
        onSubmit={(details) => {
          submitted.push(details);
          return Promise.resolve();
        }}
        onCancel={() => undefined}
      />
    ));

    expect(screen.queryByRole("checkbox", { name: "Neem oil" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("checkbox", { name: "Insecticide / H2O2" }));
    fireEvent.click(screen.getByRole("checkbox", { name: "Neem oil" }));
    fireEvent.click(screen.getByRole("checkbox", { name: "Insecticidal soap" }));
    fireEvent.click(screen.getByRole("checkbox", { name: "Neem oil" }));
    expect(screen.getByRole("checkbox", { name: "Neem oil" })).not.toBeChecked();
    fireEvent.click(screen.getByRole("checkbox", { name: "Neem oil" }));
    fireEvent.click(screen.getByRole("checkbox", { name: "Insecticide / H2O2" }));
    expect(screen.queryByRole("checkbox", { name: "Neem oil" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("checkbox", { name: "Insecticide / H2O2" }));
    expect(screen.getByRole("checkbox", { name: "Neem oil" })).not.toBeChecked();
    fireEvent.click(screen.getByRole("checkbox", { name: "Neem oil" }));
    fireEvent.click(screen.getByRole("checkbox", { name: "Insecticidal soap" }));
    fireEvent.click(screen.getByRole("button", { name: "Save operation" }));

    await waitFor(() => {
      expect(submitted).toEqual([
        {
          kind: "care",
          actions: new Set(["pesticide"]),
          pesticides: new Set(pesticides.map((pesticide) => pesticide.id)),
          moisture: "noReading",
          maybeNote: null,
        },
      ]);
    });
  });

  it("should add and edit substrate components from a repot operation", async () => {
    const addComponent = vi.fn(() =>
      Promise.resolve({
        kind: "added" as const,
        entry: {
          id: substrateComponentId("00000000-0000-4000-8000-000000000005"),
          data: {
            name: nomenclatureName("Pumice"),
            maybeInfo: nomenclatureInfo("Lightweight"),
          },
        },
      }),
    );
    const editComponent = vi.fn(() =>
      Promise.resolve({
        kind: "edited" as const,
        entry: {
          id: perliteId,
          data: {
            name: nomenclatureName("Fine perlite"),
            maybeInfo: nomenclatureInfo("Small grain"),
          },
        },
      }),
    );
    render(() => (
      <OperationForm
        initial={repot("o1", "2026-01-01T00:00:00Z").details}
        substrateComponents={substrateComponents}
        pesticides={pesticides}
        onAddSubstrateComponent={addComponent}
        onEditSubstrateComponent={editComponent}
        onAddPesticide={failPesticideAdd}
        onEditPesticide={failPesticideEdit}
        onSubmit={() => Promise.resolve()}
        onCancel={() => undefined}
      />
    ));

    fireEvent.click(screen.getByText("Manage substrate components"));
    fireEvent.input(screen.getByRole("textbox", { name: "Name for Perlite" }), {
      target: { value: " Fine perlite " },
    });
    fireEvent.input(screen.getByRole("textbox", { name: "Info for Perlite" }), {
      target: { value: " Small grain " },
    });
    fireEvent.click(screen.getByRole("button", { name: "Save Perlite" }));
    await waitFor(() => {
      expect(editComponent).toHaveBeenCalledOnce();
    });
    expect(editComponent).toHaveBeenCalledWith(perliteId, {
      name: nomenclatureName("Fine perlite"),
      maybeInfo: nomenclatureInfo("Small grain"),
    });

    fireEvent.click(screen.getByRole("button", { name: "Add substrate component" }));
    expect(screen.getByRole("alert")).toHaveTextContent("Enter a component name.");
    fireEvent.input(screen.getByRole("textbox", { name: "New substrate component name" }), {
      target: { value: " Pumice " },
    });
    fireEvent.input(screen.getByRole("textbox", { name: "New substrate component info" }), {
      target: { value: " Lightweight " },
    });
    fireEvent.click(screen.getByRole("button", { name: "Add substrate component" }));
    await waitFor(() => {
      expect(addComponent).toHaveBeenCalledOnce();
    });
    expect(addComponent).toHaveBeenCalledWith({
      name: nomenclatureName("Pumice"),
      maybeInfo: nomenclatureInfo("Lightweight"),
    });
    expect(screen.getByRole("textbox", { name: "New substrate component name" })).toHaveValue("");
  });

  it("should add and edit pesticides from a care operation", async () => {
    const addPesticide = vi.fn(() =>
      Promise.resolve({
        kind: "added" as const,
        entry: {
          id: pesticideId("00000000-0000-4000-8001-000000000005"),
          data: {
            name: nomenclatureName("Horticultural oil"),
            pesticideType: pesticideType("oil"),
            maybeInfo: nomenclatureInfo("Use in shade"),
          },
        },
      }),
    );
    const editPesticide = vi.fn(() =>
      Promise.resolve({
        kind: "edited" as const,
        entry: {
          ...pesticides[0]!,
          data: {
            name: nomenclatureName("Neem concentrate"),
            pesticideType: pesticideType("botanical"),
            maybeInfo: nomenclatureInfo("Dilute first"),
          },
        },
      }),
    );
    render(() => (
      <OperationForm
        initial={undefined}
        substrateComponents={substrateComponents}
        pesticides={pesticides}
        onAddSubstrateComponent={failComponentAdd}
        onEditSubstrateComponent={failComponentEdit}
        onAddPesticide={addPesticide}
        onEditPesticide={editPesticide}
        onSubmit={() => Promise.resolve()}
        onCancel={() => undefined}
      />
    ));

    fireEvent.click(screen.getByRole("checkbox", { name: "Insecticide / H2O2" }));
    fireEvent.click(screen.getByText("Manage pesticides"));
    fireEvent.input(screen.getByRole("textbox", { name: "Name for Neem oil" }), {
      target: { value: " Neem concentrate " },
    });
    fireEvent.input(screen.getByRole("textbox", { name: "Type for Neem oil" }), {
      target: { value: " botanical " },
    });
    fireEvent.input(screen.getByRole("textbox", { name: "Info for Neem oil" }), {
      target: { value: " Dilute first " },
    });
    fireEvent.click(screen.getByRole("button", { name: "Save Neem oil" }));
    await waitFor(() => {
      expect(editPesticide).toHaveBeenCalledOnce();
    });
    expect(editPesticide).toHaveBeenCalledWith(selectedPesticide, {
      name: nomenclatureName("Neem concentrate"),
      pesticideType: pesticideType("botanical"),
      maybeInfo: nomenclatureInfo("Dilute first"),
    });

    fireEvent.click(screen.getByRole("button", { name: "Add pesticide" }));
    expect(screen.getByRole("alert")).toHaveTextContent("Enter a pesticide name and type.");
    fireEvent.input(screen.getByRole("textbox", { name: "New pesticide name" }), {
      target: { value: " Horticultural oil " },
    });
    fireEvent.input(screen.getByRole("textbox", { name: "New pesticide type" }), {
      target: { value: " oil " },
    });
    fireEvent.input(screen.getByRole("textbox", { name: "New pesticide info" }), {
      target: { value: " Use in shade " },
    });
    fireEvent.click(screen.getByRole("button", { name: "Add pesticide" }));
    await waitFor(() => {
      expect(addPesticide).toHaveBeenCalledOnce();
    });
    expect(addPesticide).toHaveBeenCalledWith({
      name: nomenclatureName("Horticultural oil"),
      pesticideType: pesticideType("oil"),
      maybeInfo: nomenclatureInfo("Use in shade"),
    });
    expect(screen.getByRole("textbox", { name: "New pesticide name" })).toHaveValue("");
  });

  it("should report substrate catalog validation and save failures", async () => {
    const addComponent = vi
      .fn()
      .mockResolvedValueOnce({ kind: "addFailed", reason: new Error("private") })
      .mockRejectedValueOnce(new Error("private"));
    const editComponent = vi
      .fn()
      .mockResolvedValueOnce({ kind: "recordMissing" })
      .mockResolvedValueOnce({ kind: "editFailed", reason: new Error("private") })
      .mockRejectedValueOnce(new Error("private"));
    render(() => (
      <OperationForm
        initial={repot("o1", "2026-01-01T00:00:00Z").details}
        substrateComponents={substrateComponents}
        pesticides={pesticides}
        onAddSubstrateComponent={addComponent}
        onEditSubstrateComponent={editComponent}
        onAddPesticide={failPesticideAdd}
        onEditPesticide={failPesticideEdit}
        onSubmit={() => Promise.resolve()}
        onCancel={() => undefined}
      />
    ));

    fireEvent.click(screen.getByText("Manage substrate components"));
    const existingName = screen.getByRole("textbox", { name: "Name for Perlite" });
    fireEvent.input(existingName, { target: { value: " " } });
    fireEvent.click(screen.getByRole("button", { name: "Save Perlite" }));
    expect(screen.getByRole("alert")).toHaveTextContent("Enter a component name.");

    fireEvent.input(existingName, { target: { value: "Perlite" } });
    fireEvent.click(screen.getByRole("button", { name: "Save Perlite" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "This substrate component no longer exists.",
    );
    fireEvent.click(screen.getByRole("button", { name: "Save Perlite" }));
    await waitFor(() => {
      expect(screen.getByRole("alert")).toHaveTextContent(
        "The substrate component could not be saved.",
      );
      expect(editComponent).toHaveBeenCalledTimes(2);
    });
    fireEvent.click(screen.getByRole("button", { name: "Save Perlite" }));
    await waitFor(() => {
      expect(editComponent).toHaveBeenCalledTimes(3);
    });

    const newName = screen.getByRole("textbox", { name: "New substrate component name" });
    fireEvent.input(newName, { target: { value: "Pumice" } });
    fireEvent.click(screen.getByRole("button", { name: "Add substrate component" }));
    await waitFor(() => {
      expect(addComponent).toHaveBeenCalledOnce();
    });
    expect(screen.getByRole("alert")).toHaveTextContent(
      "The substrate component could not be saved.",
    );
    fireEvent.click(screen.getByRole("button", { name: "Add substrate component" }));
    await waitFor(() => {
      expect(addComponent).toHaveBeenCalledTimes(2);
    });
  });

  it("should report pesticide catalog validation and save failures", async () => {
    const addPesticide = vi
      .fn()
      .mockResolvedValueOnce({ kind: "addFailed", reason: new Error("private") })
      .mockRejectedValueOnce(new Error("private"));
    const editPesticide = vi
      .fn()
      .mockResolvedValueOnce({ kind: "recordMissing" })
      .mockResolvedValueOnce({ kind: "editFailed", reason: new Error("private") })
      .mockRejectedValueOnce(new Error("private"));
    render(() => (
      <OperationForm
        initial={undefined}
        substrateComponents={substrateComponents}
        pesticides={pesticides}
        onAddSubstrateComponent={failComponentAdd}
        onEditSubstrateComponent={failComponentEdit}
        onAddPesticide={addPesticide}
        onEditPesticide={editPesticide}
        onSubmit={() => Promise.resolve()}
        onCancel={() => undefined}
      />
    ));

    fireEvent.click(screen.getByRole("checkbox", { name: "Insecticide / H2O2" }));
    fireEvent.click(screen.getByText("Manage pesticides"));
    const existingName = screen.getByRole("textbox", { name: "Name for Neem oil" });
    fireEvent.input(existingName, { target: { value: " " } });
    fireEvent.click(screen.getByRole("button", { name: "Save Neem oil" }));
    expect(screen.getByRole("alert")).toHaveTextContent("Enter a pesticide name and type.");

    fireEvent.input(existingName, { target: { value: "Neem oil" } });
    fireEvent.click(screen.getByRole("button", { name: "Save Neem oil" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("This pesticide no longer exists.");
    fireEvent.click(screen.getByRole("button", { name: "Save Neem oil" }));
    await waitFor(() => {
      expect(screen.getByRole("alert")).toHaveTextContent("The pesticide could not be saved.");
      expect(editPesticide).toHaveBeenCalledTimes(2);
    });
    fireEvent.click(screen.getByRole("button", { name: "Save Neem oil" }));
    await waitFor(() => {
      expect(editPesticide).toHaveBeenCalledTimes(3);
    });

    fireEvent.input(screen.getByRole("textbox", { name: "New pesticide name" }), {
      target: { value: "Soap" },
    });
    fireEvent.input(screen.getByRole("textbox", { name: "New pesticide type" }), {
      target: { value: "soap" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Add pesticide" }));
    await waitFor(() => {
      expect(addPesticide).toHaveBeenCalledOnce();
    });
    expect(screen.getByRole("alert")).toHaveTextContent("The pesticide could not be saved.");
    fireEvent.click(screen.getByRole("button", { name: "Add pesticide" }));
    await waitFor(() => {
      expect(addPesticide).toHaveBeenCalledTimes(2);
    });
  });

  it("should reject a repot operation when no substrate components exist", () => {
    render(() => (
      <OperationForm
        initial={undefined}
        substrateComponents={[]}
        pesticides={[]}
        onAddSubstrateComponent={failComponentAdd}
        onEditSubstrateComponent={failComponentEdit}
        onAddPesticide={failPesticideAdd}
        onEditPesticide={failPesticideEdit}
        onSubmit={() => Promise.resolve()}
        onCancel={() => undefined}
      />
    ));

    fireEvent.change(screen.getByRole("combobox", { name: "Operation type" }), {
      target: { value: "repot" },
    });
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
        onAddSubstrateComponent={failComponentAdd}
        onEditSubstrateComponent={failComponentEdit}
        onAddPesticide={failPesticideAdd}
        onEditPesticide={failPesticideEdit}
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
