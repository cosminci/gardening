import { fireEvent, render, screen, waitFor } from "@solidjs/testing-library";
import { describe, expect, it } from "vitest";
import { OperationForm } from "../../src/app/OperationForm";
import type { OperationDetails } from "../../src/domain/Journal";
import {
  percentage,
  pesticideId,
  seededSubstrateComponentIds,
  substrate,
} from "../../src/domain/Journal";
import { care, repot } from "./JournalTestSupport";

describe("OperationForm", () => {
  it("should preserve care details while editing and allow cancellation", () => {
    let cancelled = false;
    render(() => (
      <OperationForm
        initial={care("o1", "2026-01-01T00:00:00Z", "wet").details}
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
    fireEvent.click(screen.getByRole("button", { name: "Save operation" }));
    expect(screen.getByRole("alert")).toHaveTextContent(
      "Each substrate component can only be used once.",
    );

    fireEvent.change(screen.getByRole("combobox", { name: "Component 2" }), {
      target: { value: seededSubstrateComponentIds.pineBark },
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
      target: { value: seededSubstrateComponentIds.pineBark },
    });
    fireEvent.click(screen.getByRole("button", { name: "Save operation" }));

    expect(submitted).toEqual([
      {
        kind: "repot",
        substrate: substrate([
          { component: seededSubstrateComponentIds.perlite, share: percentage(80) },
          { component: seededSubstrateComponentIds.pineBark, share: percentage(1) },
        ]),
        maybeNote: null,
      },
    ]);
  });

  it("should preserve pesticide references while editing care", async () => {
    const submitted: OperationDetails[] = [];
    const selectedPesticide = pesticideId("00000000-0000-4000-8001-000000000003");
    render(() => (
      <OperationForm
        initial={{
          kind: "care",
          actions: new Set(["pesticide"]),
          pesticides: new Set([selectedPesticide]),
          moisture: "wet",
          maybeNote: null,
        }}
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

  it("should prevent another save while a save is in progress", async () => {
    let submissions = 0;
    let finishSaving: () => void = () => undefined;
    const saving = new Promise<void>((resolve) => {
      finishSaving = resolve;
    });
    render(() => (
      <OperationForm
        initial={undefined}
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
