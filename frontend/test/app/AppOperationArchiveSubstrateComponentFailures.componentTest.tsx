import { fireEvent, render, screen, waitFor, within } from "@solidjs/testing-library";
import { describe, expect, it } from "vitest";
import { App } from "../../src/app/App";
import { instant, substrateComponentId, substrateComponentName } from "../../src/domain/Journal";
import type {
  AttentionProjection,
  SubstrateComponentArchiveResult,
  SubstrateComponentId,
} from "../../src/domain/Journal";
import { buildJournal, ficus, operationsPage } from "./JournalTestSupport";

const perliteId = substrateComponentId("00000000-0000-4000-8000-000000000003");

const unavailableFicusAttention: AttentionProjection = {
  measuredAt: instant("2026-01-01T00:00:00Z"),
  plants: [
    {
      plantId: ficus().id,
      watering: { kind: "unavailable" as const, sampleCount: 0, maybeElapsed: null },
    },
  ],
};

const openArchiveConfirmation = () => {
  fireEvent.click(screen.getByRole("button", { name: "Log operation for Fern" }));
  fireEvent.change(screen.getByRole("combobox", { name: "Operation type" }), {
    target: { value: "repot" },
  });
  fireEvent.click(screen.getByRole("button", { name: "Edit Perlite" }));
  fireEvent.click(screen.getByRole("button", { name: "Archive" }));
  const warning = screen.getByRole("alertdialog");
  fireEvent.click(within(warning).getByRole("button", { name: "Archive permanently" }));
};

const renderJournal = (componentArchiveResult: SubstrateComponentArchiveResult) => {
  const journal = buildJournal({
    attentionProjection: unavailableFicusAttention,
    getOperationsByPlantId: { p1: [operationsPage()] },
    componentArchiveResult,
  });
  render(() => (
    <App
      plants={journal}
      operations={journal}
      attention={journal}
      substrates={journal}
      pesticideCatalog={journal}
    />
  ));
};

describe("substrate component archive failures", () => {
  it("should report when a substrate component no longer exists", async () => {
    renderJournal({ kind: "componentMissing" });
    await screen.findByRole("article", { name: "Fern" });

    openArchiveConfirmation();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "This substrate component no longer exists.",
    );
    expect(screen.getByRole("dialog", { name: "Substrate component editor" })).toBeInTheDocument();
  });

  it("should report when a substrate component is already archived", async () => {
    renderJournal({ kind: "alreadyArchived" });
    await screen.findByRole("article", { name: "Fern" });

    openArchiveConfirmation();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "This substrate component was already archived.",
    );
    expect(screen.getByRole("dialog", { name: "Substrate component editor" })).toBeInTheDocument();
  });

  it("should report an archive failure without showing its reason", async () => {
    renderJournal({ kind: "archiveFailed", reason: new Error("private details") });
    await screen.findByRole("article", { name: "Fern" });

    openArchiveConfirmation();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "The substrate component could not be archived.",
    );
    expect(screen.queryByText("private details")).not.toBeInTheDocument();
  });

  it("should report an unexpectedly rejected archive without showing its reason", async () => {
    const journal = {
      ...buildJournal({
        attentionProjection: unavailableFicusAttention,
        getOperationsByPlantId: { p1: [operationsPage()] },
      }),
      archiveSubstrateComponent: () => Promise.reject(new Error("private details")),
    };
    render(() => (
      <App
        plants={journal}
        operations={journal}
        attention={journal}
        substrates={journal}
        pesticideCatalog={journal}
      />
    ));
    await screen.findByRole("article", { name: "Fern" });

    openArchiveConfirmation();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "The substrate component could not be archived.",
    );
    expect(screen.queryByText("private details")).not.toBeInTheDocument();
  });

  it("should archive a substrate component and close its editor while the operation sheet stays open", async () => {
    renderJournal({
      kind: "archived",
      entry: {
        id: perliteId,
        data: { name: substrateComponentName("Perlite"), maybeInfo: null },
        status: "archived",
      },
    });
    await screen.findByRole("article", { name: "Fern" });

    fireEvent.click(screen.getByRole("button", { name: "Log operation for Fern" }));
    const operation = screen.getByRole("dialog", { name: "Operation editor" });
    fireEvent.change(screen.getByRole("combobox", { name: "Operation type" }), {
      target: { value: "repot" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Edit Perlite" }));
    fireEvent.click(screen.getByRole("button", { name: "Archive" }));
    const warning = screen.getByRole("alertdialog");
    fireEvent.click(within(warning).getByRole("button", { name: "Archive permanently" }));

    await waitFor(() => {
      expect(screen.queryByRole("dialog", { name: "Substrate component editor" })).toBeNull();
    });
    expect(screen.queryByRole("alertdialog")).toBeNull();
    expect(screen.getByRole("dialog", { name: "Operation editor" })).toBe(operation);
  });

  it("should cancel archiving a substrate component and restore focus without writing", async () => {
    const archivedComponents: SubstrateComponentId[] = [];
    const journal = buildJournal({
      attentionProjection: unavailableFicusAttention,
      getOperationsByPlantId: { p1: [operationsPage()] },
      archivedComponents,
    });
    render(() => (
      <App
        plants={journal}
        operations={journal}
        attention={journal}
        substrates={journal}
        pesticideCatalog={journal}
      />
    ));
    await screen.findByRole("article", { name: "Fern" });

    fireEvent.click(screen.getByRole("button", { name: "Log operation for Fern" }));
    fireEvent.change(screen.getByRole("combobox", { name: "Operation type" }), {
      target: { value: "repot" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Edit Perlite" }));
    const archiveControl = screen.getByRole("button", { name: "Archive" });
    archiveControl.focus();
    fireEvent.click(archiveControl);
    const warning = screen.getByRole("alertdialog");
    fireEvent.click(within(warning).getByRole("button", { name: "Cancel" }));

    await waitFor(() => {
      expect(archiveControl).toHaveFocus();
    });
    expect(screen.getByRole("dialog", { name: "Substrate component editor" })).toBeInTheDocument();
    expect(screen.queryByRole("alertdialog")).toBeNull();
    expect(archivedComponents).toEqual([]);
  });
});
