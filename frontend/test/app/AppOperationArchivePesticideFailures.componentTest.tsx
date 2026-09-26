import { fireEvent, render, screen, within } from "@solidjs/testing-library";
import { describe, expect, it } from "vitest";
import { App } from "../../src/app/App";
import { instant, pesticideName, pesticideId } from "../../src/domain/Journal";
import type { AttentionProjection, PesticideArchiveResult } from "../../src/domain/Journal";
import { buildJournal, ficus, noopPhotoClient, operationsPage } from "./JournalTestSupport";

const unavailableFicusAttention: AttentionProjection = {
  measuredAt: instant("2026-01-01T00:00:00Z"),
  plants: [
    {
      plant: ficus().id,
      watering: { kind: "unavailable" as const, sampleCount: 0, maybeElapsed: null },
    },
  ],
};

const neemId = pesticideId("00000000-0000-4000-8001-000000000003");
const pesticides = [
  {
    id: neemId,
    data: {
      name: pesticideName("Neem oil"),
      type: "insecticide" as const,
      maybeInfo: null,
    },
    status: "active" as const,
  },
];

const openArchiveConfirmation = () => {
  fireEvent.click(screen.getByRole("button", { name: "Log operation for Fern" }));
  fireEvent.click(screen.getByRole("checkbox", { name: "Pesticide" }));
  fireEvent.click(screen.getByRole("button", { name: "Edit Neem oil" }));
  fireEvent.click(screen.getByRole("button", { name: "Archive" }));
  const warning = screen.getByRole("alertdialog");
  fireEvent.click(within(warning).getByRole("button", { name: "Archive permanently" }));
};

const renderJournal = (pesticideArchiveResult: PesticideArchiveResult) => {
  const journal = buildJournal({
    attentionProjection: unavailableFicusAttention,
    getOperationsByPlantId: { p1: [operationsPage()] },
    getPesticidesResult: { kind: "read", entries: pesticides },
    pesticideArchiveResult,
  });
  render(() => (
    <App
      plants={journal}
      operations={journal}
      attention={journal}
      substrates={journal}
      pesticideCatalog={journal}
      photos={noopPhotoClient}
    />
  ));
};

describe("pesticide archive failures", () => {
  it("should report when a pesticide no longer exists", async () => {
    renderJournal({ kind: "pesticideMissing" });
    await screen.findByRole("article", { name: "Fern" });

    openArchiveConfirmation();

    expect(await screen.findByRole("alert")).toHaveTextContent("This pesticide no longer exists.");
    expect(screen.getByRole("dialog", { name: "Pesticide editor" })).toBeInTheDocument();
  });

  it("should report when a pesticide is already archived", async () => {
    renderJournal({ kind: "alreadyArchived" });
    await screen.findByRole("article", { name: "Fern" });

    openArchiveConfirmation();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "This pesticide was already archived.",
    );
    expect(screen.getByRole("dialog", { name: "Pesticide editor" })).toBeInTheDocument();
  });

  it("should report an archive failure without showing its reason", async () => {
    renderJournal({ kind: "archiveFailed", reason: new Error("private details") });
    await screen.findByRole("article", { name: "Fern" });

    openArchiveConfirmation();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "The pesticide could not be archived.",
    );
    expect(screen.queryByText("private details")).not.toBeInTheDocument();
  });

  it("should report an unexpectedly rejected archive without showing its reason", async () => {
    const journal = {
      ...buildJournal({
        attentionProjection: unavailableFicusAttention,
        getOperationsByPlantId: { p1: [operationsPage()] },
        getPesticidesResult: { kind: "read" as const, entries: pesticides },
      }),
      archivePesticide: () => Promise.reject(new Error("private details")),
    };
    render(() => (
      <App
        plants={journal}
        operations={journal}
        attention={journal}
        substrates={journal}
        pesticideCatalog={journal}
        photos={noopPhotoClient}
      />
    ));
    await screen.findByRole("article", { name: "Fern" });

    openArchiveConfirmation();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "The pesticide could not be archived.",
    );
    expect(screen.queryByText("private details")).not.toBeInTheDocument();
  });
});
