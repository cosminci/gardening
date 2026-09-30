import { fireEvent, render, screen, waitFor } from "@solidjs/testing-library";
import { describe, expect, it } from "vitest";
import { App } from "../../src/app/App";
import { instant } from "../../src/domain/Journal";
import type * as Journal from "../../src/domain/Journal";
import * as JournalFixtures from "./JournalTestSupport";

const bothPlantsAttention: Journal.AttentionProjection = {
  measuredAt: instant("2026-01-01T00:00:00Z"),
  plants: [
    {
      plant: JournalFixtures.ficus().id,
      watering: { kind: "unavailable", sampleCount: 0, maybeElapsed: null },
    },
    {
      plant: JournalFixtures.monstera().id,
      watering: { kind: "unavailable", sampleCount: 0, maybeElapsed: null },
    },
  ],
};

const renderJournal = (journal: ReturnType<typeof JournalFixtures.buildJournal>) =>
  render(() => (
    <App
      plants={journal}
      operations={journal}
      attention={journal}
      substrates={journal}
      pesticideCatalog={journal}
      photos={JournalFixtures.noopPhotoClient}
    />
  ));

describe("searching the journal", () => {
  it("should filter the active view live by nickname, species, or location, case-insensitively", async () => {
    const journal = JournalFixtures.buildJournal({
      attentionProjection: bothPlantsAttention,
      getOperationsByPlantId: {
        p1: [JournalFixtures.operationsPage()],
        p2: [JournalFixtures.operationsPage()],
      },
    });

    renderJournal(journal);

    await screen.findByRole("article", { name: "Fern" });
    const search = screen.getByRole("searchbox");

    fireEvent.input(search, { target: { value: "fern" } });
    expect(screen.getByRole("article", { name: "Fern" })).toBeInTheDocument();
    expect(screen.queryByRole("article", { name: "Monstera deliciosa" })).toBeNull();

    fireEvent.input(search, { target: { value: "KITCHEN" } });
    expect(screen.queryByRole("article", { name: "Fern" })).toBeNull();
    expect(screen.getByRole("article", { name: "Monstera deliciosa" })).toBeInTheDocument();

    fireEvent.input(search, { target: { value: "" } });
    expect(screen.getByRole("article", { name: "Fern" })).toBeInTheDocument();
    expect(screen.getByRole("article", { name: "Monstera deliciosa" })).toBeInTheDocument();
  });

  it("should show an accessible empty-state message when no plant matches", async () => {
    const journal = JournalFixtures.buildJournal({
      attentionProjection: bothPlantsAttention,
      getOperationsByPlantId: {
        p1: [JournalFixtures.operationsPage()],
        p2: [JournalFixtures.operationsPage()],
      },
    });

    renderJournal(journal);

    await screen.findByRole("article", { name: "Fern" });
    fireEvent.input(screen.getByRole("searchbox"), { target: { value: "cactus" } });

    const message = screen.getByText("No plants match your search.");
    expect(message).toHaveAttribute("role", "status");
    expect(screen.queryByRole("article")).toBeNull();
  });

  it("should leave the garden/cemetery toggle counts unaffected by the filter", async () => {
    const journal = JournalFixtures.buildJournal({
      attentionProjection: bothPlantsAttention,
      getOperationsByPlantId: {
        p1: [JournalFixtures.operationsPage()],
        p2: [JournalFixtures.operationsPage()],
      },
    });

    renderJournal(journal);

    await screen.findByRole("article", { name: "Fern" });
    fireEvent.input(screen.getByRole("searchbox"), { target: { value: "fern" } });

    expect(screen.getByRole("button", { name: /Garden.*2 plants/ })).toBeInTheDocument();
  });

  it("should filter the cemetery view live by species, nickname, or location", async () => {
    const archivedFicus = {
      ...JournalFixtures.ficus(),
      details: { ...JournalFixtures.ficus().details, status: "archived" as const },
    };
    const archivedMonstera = {
      ...JournalFixtures.monstera(),
      details: { ...JournalFixtures.monstera().details, status: "archived" as const },
    };
    const base = JournalFixtures.buildJournal({
      attentionProjection: { measuredAt: instant("2026-01-01T00:00:00Z"), plants: [] },
      getOperationsByPlantId: {
        p1: [JournalFixtures.operationsPage()],
        p2: [JournalFixtures.operationsPage()],
      },
    });
    const journal = {
      ...base,
      getPlants: (status?: string) =>
        Promise.resolve({
          kind: "read" as const,
          plants: status === "archived" ? [archivedFicus, archivedMonstera] : [],
        }),
      getArchivedCount: () => Promise.resolve({ kind: "read" as const, count: 2 }),
    };

    renderJournal(journal);

    fireEvent.click(await screen.findByRole("button", { name: /Cemetery.*2 plants/ }));
    await screen.findByRole("article", { name: "Fern" });
    await screen.findByRole("article", { name: "Monstera deliciosa" });

    const search = screen.getByRole("searchbox");
    fireEvent.input(search, { target: { value: "kitchen" } });

    expect(screen.getByRole("article", { name: "Monstera deliciosa" })).toBeInTheDocument();
    expect(screen.queryByRole("article", { name: "Fern" })).toBeNull();

    fireEvent.input(search, { target: { value: "" } });
    expect(screen.getByRole("article", { name: "Fern" })).toBeInTheDocument();
    expect(screen.getByRole("article", { name: "Monstera deliciosa" })).toBeInTheDocument();
  });

  it("should clear the search query when switching between garden and cemetery", async () => {
    const archivedPlant = {
      ...JournalFixtures.monstera(),
      details: { ...JournalFixtures.monstera().details, status: "archived" as const },
    };
    const base = JournalFixtures.buildJournal({
      attentionProjection: { measuredAt: instant("2026-01-01T00:00:00Z"), plants: [] },
      getOperationsByPlantId: {
        p1: [JournalFixtures.operationsPage()],
        p2: [JournalFixtures.operationsPage()],
      },
    });
    const journal = {
      ...base,
      getPlants: (status?: string) =>
        Promise.resolve({
          kind: "read" as const,
          plants: status === "archived" ? [archivedPlant] : [JournalFixtures.ficus()],
        }),
      getArchivedCount: () => Promise.resolve({ kind: "read" as const, count: 1 }),
    };

    renderJournal(journal);

    await screen.findByRole("article", { name: "Fern" });
    fireEvent.input(screen.getByRole("searchbox"), { target: { value: "fern" } });
    expect(screen.getByRole("searchbox")).toHaveValue("fern");

    fireEvent.click(await screen.findByRole("button", { name: /Cemetery/ }));
    await screen.findByRole("region", { name: "Cemetery" });

    expect(screen.getByRole("searchbox")).toHaveValue("");
    expect(screen.getByRole("article", { name: "Monstera deliciosa" })).toBeInTheDocument();

    fireEvent.input(screen.getByRole("searchbox"), { target: { value: "monstera" } });
    fireEvent.click(screen.getByRole("button", { name: /Garden/ }));

    await waitFor(() => {
      expect(screen.getByRole("searchbox")).toHaveValue("");
    });
    expect(screen.getByRole("article", { name: "Fern" })).toBeInTheDocument();
  });
});
