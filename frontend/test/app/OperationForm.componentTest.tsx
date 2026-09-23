import * as Testing from "@solidjs/testing-library";
import { afterEach, describe, expect, it, vi } from "vitest";
import { OperationForm } from "../../src/app/OperationForm";
import * as Journal from "../../src/domain/Journal";
import { care, repot } from "./JournalTestSupport";

const perliteId = Journal.substrateComponentId("00000000-0000-4000-8000-000000000003");
const pineBarkId = Journal.substrateComponentId("00000000-0000-4000-8000-000000000004");
const substrateComponents: readonly Journal.SubstrateComponent[] = [
  {
    id: perliteId,
    data: {
      name: Journal.nomenclatureName("Perlite"),
      maybeInfo: Journal.nomenclatureInfo("Improves drainage.\nUse up to 30%."),
    },
  },
  { id: pineBarkId, data: { name: Journal.nomenclatureName("Pine bark"), maybeInfo: null } },
];
const neemId = Journal.pesticideId("00000000-0000-4000-8001-000000000003");
const soapId = Journal.pesticideId("00000000-0000-4000-8001-000000000004");
const pesticides: readonly Journal.Pesticide[] = [
  {
    id: neemId,
    data: {
      name: Journal.nomenclatureName("Neem oil"),
      pesticideType: "insecticide",
      maybeInfo: Journal.nomenclatureInfo("Dilute before use.\nApply weekly."),
    },
  },
  {
    id: soapId,
    data: {
      name: Journal.nomenclatureName("Insecticidal soap"),
      pesticideType: "insecticide",
      maybeInfo: null,
    },
  },
];

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllEnvs();
});

describe("OperationForm", () => {
  it("should log the default local minute and a keyboard-edited local minute as instants", async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(2026, 8, 23, 14, 35, 48));
    const submitted: Journal.Instant[] = [];
    Testing.render(() => (
      <OperationForm
        initial={undefined}
        substrateComponents={substrateComponents}
        pesticides={pesticides}
        onAddSubstrateComponent={() => undefined}
        onEditSubstrateComponent={() => undefined}
        onAddPesticide={() => undefined}
        onEditPesticide={() => undefined}
        onSubmit={(_, date) => {
          submitted.push(date);
          return Promise.resolve();
        }}
        onCancel={() => undefined}
      />
    ));

    const date = Testing.screen.getByLabelText("Date and time");
    expect(date).toHaveAttribute("type", "datetime-local");
    expect(date).toHaveValue("2026-09-23T14:35");
    date.focus();
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Save operation" }));
    await Promise.resolve();
    Testing.fireEvent.input(date, { target: { value: "2026-08-14T10:07" } });
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Save operation" }));

    const expectedDates = [
      Journal.instant(new Date(2026, 8, 23, 14, 35).toISOString()),
      Journal.instant(new Date(2026, 7, 14, 10, 7).toISOString()),
    ];
    expect(submitted).toEqual(expectedDates);
  });

  it("should keep missing and invalid operation dates in the form with an accessible error", () => {
    const submitted: Journal.Instant[] = [];
    Testing.render(() => (
      <OperationForm
        initial={undefined}
        substrateComponents={substrateComponents}
        pesticides={pesticides}
        onAddSubstrateComponent={() => undefined}
        onEditSubstrateComponent={() => undefined}
        onAddPesticide={() => undefined}
        onEditPesticide={() => undefined}
        onSubmit={(_, date) => {
          submitted.push(date);
          return Promise.resolve();
        }}
        onCancel={() => undefined}
      />
    ));

    const date = Testing.screen.getByLabelText("Date and time");
    Testing.fireEvent.input(date, { target: { value: "" } });
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Save operation" }));
    const alert = Testing.screen.getByRole("alert");
    expect(date).toHaveAttribute("aria-invalid", "true");
    expect(date).toHaveAttribute("aria-describedby", alert.id);
    expect(alert).toHaveTextContent("Enter a valid date and time.");
    Testing.fireEvent.input(date, { target: { value: "not a date" } });
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Save operation" }));
    vi.stubEnv("TZ", "America/New_York");
    Testing.fireEvent.input(date, { target: { value: "2026-03-08T02:30" } });
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Save operation" }));
    expect(Testing.screen.getByRole("alert")).toHaveTextContent("Enter a valid date and time.");
    expect(submitted).toEqual([]);
    expect(Testing.screen.getByRole("form", { name: "Log operation" })).toBeInTheDocument();
  });

  it("should preserve care details, omit None, and allow collapsing", () => {
    let cancelled = false;
    Testing.render(() => (
      <OperationForm
        initial={care({ id: "o1", date: "2026-01-01T00:00:00Z", moisture: "wet" }).details}
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

    expect(Testing.screen.getByRole("heading", { name: "Edit operation" })).toBeInTheDocument();
    expect(Testing.screen.queryByText("Amend entry")).not.toBeInTheDocument();
    expect(
      Testing.screen.queryByText("Record what changed while the details are still fresh."),
    ).not.toBeInTheDocument();
    expect(Testing.screen.getByRole("combobox", { name: "Operation type" })).toBeDisabled();
    expect(Testing.screen.getByRole("combobox", { name: "Moisture" })).toHaveValue("wet");
    expect(Testing.screen.queryByRole("checkbox", { name: "None" })).not.toBeInTheDocument();
    const actionChoices = Testing.within(
      Testing.screen.getByRole("group", { name: "Care actions" }),
    )
      .getAllByRole("checkbox")
      .map((choice) => choice.parentElement?.textContent);
    expect(actionChoices).toEqual(["Watered", "Fertilized", "Pruned", "Pesticide"]);

    const watered = Testing.screen.getByRole("checkbox", { name: "Watered" });
    expect(watered).toBeChecked();
    Testing.fireEvent.click(watered);
    expect(watered).not.toBeChecked();
    Testing.fireEvent.click(
      Testing.screen.getByRole("button", { name: "Collapse operation editor" }),
    );
    expect(cancelled).toBe(true);
  });

  it("should require distinct substrate components with shares totaling at most 100", () => {
    const submitted: Journal.OperationDetails[] = [];
    Testing.render(() => (
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

    expect(Testing.screen.getByRole("tooltip", { name: /Improves drainage/ })).toHaveTextContent(
      "Improves drainage. Use up to 30%.",
    );
    const substrateInfo = Testing.screen.getByRole("button", { name: "Information about Perlite" });
    substrateInfo.focus();
    Testing.fireEvent.keyDown(substrateInfo, { key: "Enter" });
    expect(substrateInfo).toHaveFocus();
    Testing.fireEvent.keyDown(substrateInfo, { key: "Escape" });
    expect(substrateInfo).not.toHaveFocus();
    const share = Testing.screen.getByRole("spinbutton", { name: "Component 1 share" });
    share.focus();
    Testing.fireEvent.input(share, { target: { value: "0" } });
    expect(document.activeElement).toBe(share);
    Testing.fireEvent.submit(Testing.screen.getByRole("form", { name: "Edit operation" }));
    expect(Testing.screen.getByRole("alert")).toHaveTextContent(
      "Each substrate share must be a whole number from 1 to 100%.",
    );

    Testing.fireEvent.input(share, { target: { value: "100" } });
    expect(Testing.screen.queryByText("Component", { exact: true })).not.toBeInTheDocument();
    expect(Testing.screen.queryByText("Share", { exact: true })).not.toBeInTheDocument();
    expect(Testing.screen.getByText("%")).toBeInTheDocument();
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Extend mix" }));
    expect(Testing.screen.getByRole("combobox", { name: "Component 2" })).toHaveValue(pineBarkId);
    Testing.fireEvent.change(Testing.screen.getByRole("combobox", { name: "Component 2" }), {
      target: { value: perliteId },
    });
    const perliteInfoControls = Testing.screen.getAllByRole("button", {
      name: "Information about Perlite",
    });
    expect(perliteInfoControls[0]).not.toHaveAttribute(
      "aria-describedby",
      perliteInfoControls[1]?.getAttribute("aria-describedby"),
    );
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Save operation" }));
    expect(Testing.screen.getByRole("alert")).toHaveTextContent(
      "Each substrate component can only be used once.",
    );

    Testing.fireEvent.change(Testing.screen.getByRole("combobox", { name: "Component 2" }), {
      target: { value: pineBarkId },
    });
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Save operation" }));
    expect(Testing.screen.getByRole("alert")).toHaveTextContent(
      "Substrate shares cannot total more than 100%.",
    );

    Testing.fireEvent.input(share, { target: { value: "80" } });
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Remove component 2" }));
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Extend mix" }));
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Save operation" }));
    expect(submitted).toEqual([
      {
        kind: "repot",
        substrate: Journal.substrate([
          { component: perliteId, share: Journal.percentage(80) },
          { component: pineBarkId, share: Journal.percentage(1) },
        ]),
        maybeNote: null,
      },
    ]);
  });

  it("should reveal pesticide choices last and clear them when deselected", async () => {
    const submitted: Journal.OperationDetails[] = [];
    let addRequests = 0;
    const editRequests: Journal.Pesticide[] = [];
    Testing.render(() => (
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

    expect(Testing.screen.queryByRole("checkbox", { name: "Neem oil" })).not.toBeInTheDocument();
    Testing.fireEvent.click(Testing.screen.getByRole("checkbox", { name: "Pesticide" }));
    expect(Testing.screen.getByRole("tooltip", { name: /Dilute before use/ })).toHaveTextContent(
      "Dilute before use. Apply weekly.",
    );
    expect(
      Testing.screen
        .getByRole("combobox", { name: "Moisture" })
        .compareDocumentPosition(Testing.screen.getByRole("checkbox", { name: "Neem oil" })) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(Testing.screen.getAllByLabelText("Insecticide")).toHaveLength(2);
    const pesticideInfo = Testing.screen.getByRole("button", {
      name: "Information about Neem oil",
    });
    pesticideInfo.focus();
    Testing.fireEvent.keyDown(pesticideInfo, { key: "Enter" });
    expect(pesticideInfo).toHaveFocus();
    Testing.fireEvent.keyDown(pesticideInfo, { key: "Escape" });
    expect(pesticideInfo).not.toHaveFocus();
    Testing.fireEvent.click(Testing.screen.getByRole("checkbox", { name: "Neem oil" }));
    Testing.fireEvent.click(Testing.screen.getByRole("checkbox", { name: "Insecticidal soap" }));
    Testing.fireEvent.click(Testing.screen.getByRole("checkbox", { name: "Neem oil" }));
    Testing.fireEvent.click(Testing.screen.getByRole("checkbox", { name: "Neem oil" }));
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Edit Neem oil" }));
    expect(editRequests).toEqual([pesticides[0]]);
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Define new pesticide" }));
    expect(addRequests).toBe(1);
    Testing.fireEvent.click(Testing.screen.getByRole("checkbox", { name: "Pesticide" }));
    expect(Testing.screen.queryByRole("checkbox", { name: "Neem oil" })).not.toBeInTheDocument();
    Testing.fireEvent.click(Testing.screen.getByRole("checkbox", { name: "Pesticide" }));
    expect(Testing.screen.getByRole("checkbox", { name: "Neem oil" })).not.toBeChecked();
    Testing.fireEvent.click(Testing.screen.getByRole("checkbox", { name: "Neem oil" }));
    Testing.fireEvent.click(Testing.screen.getByRole("checkbox", { name: "Insecticidal soap" }));
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Save operation" }));

    await Testing.waitFor(() => {
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
    const submitted: Journal.OperationDetails[] = [];
    Testing.render(() => (
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

    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Save operation" }));
    await Testing.waitFor(() => {
      expect(submitted[0]).toMatchObject({ actions: new Set() });
    });
  });

  it("should open substrate creation and reject a repot with no components", () => {
    let addRequests = 0;
    Testing.render(() => (
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

    Testing.fireEvent.change(Testing.screen.getByRole("combobox", { name: "Operation type" }), {
      target: { value: "repot" },
    });
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Define new component" }));
    expect(addRequests).toBe(1);
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Save operation" }));
    expect(Testing.screen.getByRole("alert")).toHaveTextContent(
      "Add at least one substrate component.",
    );
  });

  it("should prevent another save while a save is in progress", async () => {
    let submissions = 0;
    let finishSaving: () => void = () => undefined;
    const saving = new Promise<void>((resolve) => {
      finishSaving = resolve;
    });
    Testing.render(() => (
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

    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Save operation" }));
    const savingButton = Testing.screen.getByRole("button", { name: "Saving…" });
    expect(savingButton).toBeDisabled();
    Testing.fireEvent.click(savingButton);
    expect(submissions).toBe(1);

    finishSaving();
    await Testing.waitFor(() => {
      expect(Testing.screen.getByRole("button", { name: "Save operation" })).toBeEnabled();
    });
  });
});
