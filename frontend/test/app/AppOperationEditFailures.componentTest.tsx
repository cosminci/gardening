import { fireEvent, render, screen } from "@solidjs/testing-library";
import { describe, expect, it } from "vitest";
import { App } from "../../src/app/App";
import { instant } from "../../src/domain/Journal";
import type { AttentionProjection } from "../../src/domain/Journal";
import { buildJournal, ficus, operationsPage, repot } from "./JournalTestSupport";

const unavailableFicusAttention: AttentionProjection = {
  measuredAt: instant("2026-01-01T00:00:00Z"),
  plants: [
    {
      plantId: ficus().id,
      watering: { kind: "unavailable" as const, sampleCount: 0, maybeElapsed: null },
    },
  ],
};

describe("operation edit failures", () => {
  it("should report when an operation no longer exists", async () => {
    const existing = repot("o1", "2026-03-03T00:00:00Z");
    const journal = buildJournal({
      attentionProjection: unavailableFicusAttention,
      getOperationsByPlantId: { p1: [operationsPage([existing])] },
      editOperationResult: { kind: "operationMissing" },
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
    await screen.findByText("3rd of March");

    fireEvent.click(
      screen.getByRole("button", { name: "Edit recent repot operation 1 from 3rd of March" }),
    );
    fireEvent.click(screen.getByRole("button", { name: "Save operation" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("This operation no longer exists.");
  });

  it("should report an attempt to change an operation type", async () => {
    const existing = repot("o1", "2026-03-03T00:00:00Z");
    const journal = buildJournal({
      attentionProjection: unavailableFicusAttention,
      getOperationsByPlantId: { p1: [operationsPage([existing])] },
      editOperationResult: { kind: "operationTypeMismatch" },
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
    await screen.findByText("3rd of March");

    fireEvent.click(
      screen.getByRole("button", { name: "Edit recent repot operation 1 from 3rd of March" }),
    );
    fireEvent.click(screen.getByRole("button", { name: "Save operation" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "The operation type cannot be changed.",
    );
  });

  it("should report an edit failure without showing its reason", async () => {
    const reason = new Error("private details");
    const existing = repot("o1", "2026-03-03T00:00:00Z");
    const journal = buildJournal({
      attentionProjection: unavailableFicusAttention,
      getOperationsByPlantId: { p1: [operationsPage([existing])] },
      editOperationResult: { kind: "editFailed", reason },
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
    await screen.findByText("3rd of March");

    fireEvent.click(
      screen.getByRole("button", { name: "Edit recent repot operation 1 from 3rd of March" }),
    );
    fireEvent.click(screen.getByRole("button", { name: "Save operation" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("The operation could not be saved.");
    expect(screen.queryByText("private details")).not.toBeInTheDocument();
  });
});
