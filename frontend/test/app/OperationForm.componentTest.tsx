import { fireEvent, render, screen, waitFor, within } from "@solidjs/testing-library";
import { describe, expect, it } from "vitest";
import { OperationForm } from "../../src/app/OperationForm";
import type { OperationDetails, Pesticide, SubstrateComponent } from "../../src/domain/Journal";
import {
  nomenclatureName,
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
const neemId = pesticideId("00000000-0000-4000-8001-000000000003");
const soapId = pesticideId("00000000-0000-4000-8001-000000000004");
const pesticides: readonly Pesticide[] = [
  {
    id: neemId,
    data: {
      name: nomenclatureName("Neem oil"),
      pesticideType: pesticideType("organic"),
      maybeInfo: null,
    },
  },
  {
    id: soapId,
    data: {
      name: nomenclatureName("Insecticidal soap"),
      pesticideType: pesticideType("soap"),
      maybeInfo: null,
    },
  },
];

describe("OperationForm", () => {
  it("should preserve care details, omit None, and allow cancellation", () => {
    let cancelled = false;
    render(() => (
      <OperationForm
        initial={care("o1", "2026-01-01T00:00:00Z", "wet").details}
        substrateComponents={substrateComponents}
        pesticides={pesticides}
        onManageSubstrateComponents={() => undefined}
        onManagePesticides={() => undefined}
        onSubmit={() => Promise.resolve()}
        onCancel={() => {
          cancelled = true;
        }}
      />
    ));

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
        onManageSubstrateComponents={() => undefined}
        onManagePesticides={() => undefined}
        onSubmit={(details) => {
          submitted.push(details);
          return Promise.resolve();
        }}
        onCancel={() => undefined}
      />
    ));

    const share = screen.getByRole("spinbutton", { name: "Component 1 share" });
    share.focus();
    fireEvent.input(share, { target: { value: "0" } });
    expect(document.activeElement).toBe(share);
    fireEvent.submit(screen.getByRole("form", { name: "Edit operation" }));
    expect(screen.getByRole("alert")).toHaveTextContent(
      "Each substrate share must be a whole number from 1 to 100%.",
    );

    fireEvent.input(share, { target: { value: "100" } });
    fireEvent.click(screen.getByRole("button", { name: "Add component" }));
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

    fireEvent.input(share, { target: { value: "80" } });
    fireEvent.click(screen.getByRole("button", { name: "Remove component 2" }));
    fireEvent.click(screen.getByRole("button", { name: "Add component" }));
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
    let manageRequests = 0;
    render(() => (
      <OperationForm
        initial={undefined}
        substrateComponents={substrateComponents}
        pesticides={pesticides}
        onManageSubstrateComponents={() => undefined}
        onManagePesticides={() => {
          manageRequests += 1;
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
    fireEvent.click(screen.getByRole("checkbox", { name: "Neem oil" }));
    fireEvent.click(screen.getByRole("checkbox", { name: "Insecticidal soap" }));
    fireEvent.click(screen.getByRole("checkbox", { name: "Neem oil" }));
    fireEvent.click(screen.getByRole("checkbox", { name: "Neem oil" }));
    fireEvent.click(screen.getByRole("button", { name: "Manage" }));
    expect(manageRequests).toBe(1);
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
        onManageSubstrateComponents={() => undefined}
        onManagePesticides={() => undefined}
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

  it("should open substrate management and reject a repot with no components", () => {
    let manageRequests = 0;
    render(() => (
      <OperationForm
        initial={undefined}
        substrateComponents={[]}
        pesticides={[]}
        onManageSubstrateComponents={() => {
          manageRequests += 1;
        }}
        onManagePesticides={() => undefined}
        onSubmit={() => Promise.resolve()}
        onCancel={() => undefined}
      />
    ));

    fireEvent.change(screen.getByRole("combobox", { name: "Operation type" }), {
      target: { value: "repot" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Manage" }));
    expect(manageRequests).toBe(1);
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
        onManageSubstrateComponents={() => undefined}
        onManagePesticides={() => undefined}
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
