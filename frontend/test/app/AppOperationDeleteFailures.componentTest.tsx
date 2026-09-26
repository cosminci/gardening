import { fireEvent, render, screen, within } from "@solidjs/testing-library";
import { describe, expect, it } from "vitest";
import { App } from "../../src/app/App";
import { instant } from "../../src/domain/Journal";
import type { AttentionProjection } from "../../src/domain/Journal";
import { buildJournal, care, ficus, noopPhotoClient, operationsPage } from "./JournalTestSupport";

const unavailableFicusAttention: AttentionProjection = {
  measuredAt: instant("2026-01-01T00:00:00Z"),
  plants: [
    {
      plantId: ficus().id,
      watering: { kind: "unavailable" as const, sampleCount: 0, maybeElapsed: null },
    },
  ],
};

const openDeleteConfirmation = () => {
  fireEvent.click(
    screen.getByRole("button", { name: "Edit recent care operation 1 from 3rd of March" }),
  );
  fireEvent.click(screen.getByRole("button", { name: "Delete" }));
  const warning = screen.getByRole("alertdialog");
  fireEvent.click(within(warning).getByRole("button", { name: "Delete permanently" }));
};

describe("operation delete failures", () => {
  it("should report when an operation no longer exists", async () => {
    const existing = care({ id: "o1", date: "2026-03-03T00:00:00Z", moisture: "wet" });
    const journal = buildJournal({
      attentionProjection: unavailableFicusAttention,
      getOperationsByPlantId: { p1: [operationsPage([existing])] },
      deleteOperationResult: { kind: "operationMissing" },
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
    await screen.findByText("3rd of March");

    openDeleteConfirmation();

    expect(await screen.findByRole("alert")).toHaveTextContent("This operation no longer exists.");
    expect(screen.getByRole("dialog", { name: "Operation editor" })).toBeInTheDocument();
  });

  it("should reject deleting the plant's current latest repot", async () => {
    const existing = care({ id: "o1", date: "2026-03-03T00:00:00Z", moisture: "wet" });
    const journal = buildJournal({
      attentionProjection: unavailableFicusAttention,
      getOperationsByPlantId: { p1: [operationsPage([existing])] },
      deleteOperationResult: { kind: "cannotDeleteLatestRepot" },
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
    await screen.findByText("3rd of March");

    openDeleteConfirmation();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "This is the plant's current repot and cannot be deleted.",
    );
    expect(screen.getByRole("dialog", { name: "Operation editor" })).toBeInTheDocument();
    expect(screen.getByText("3rd of March")).toBeInTheDocument();
  });

  it("should report a delete failure without showing its reason", async () => {
    const reason = new Error("private details");
    const existing = care({ id: "o1", date: "2026-03-03T00:00:00Z", moisture: "wet" });
    const journal = buildJournal({
      attentionProjection: unavailableFicusAttention,
      getOperationsByPlantId: { p1: [operationsPage([existing])] },
      deleteOperationResult: { kind: "deleteFailed", reason },
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
    await screen.findByText("3rd of March");

    openDeleteConfirmation();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "The operation could not be deleted.",
    );
    expect(screen.queryByText("private details")).not.toBeInTheDocument();
  });

  it("should report an unexpectedly rejected delete without showing its reason", async () => {
    const existing = care({ id: "o1", date: "2026-03-03T00:00:00Z", moisture: "wet" });
    const journal = {
      ...buildJournal({
        attentionProjection: unavailableFicusAttention,
        getOperationsByPlantId: { p1: [operationsPage([existing])] },
      }),
      deleteOperation: () => Promise.reject(new Error("private details")),
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
    await screen.findByText("3rd of March");

    openDeleteConfirmation();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "The operation could not be deleted.",
    );
    expect(screen.queryByText("private details")).not.toBeInTheDocument();
  });

  it("should report when the journal cannot refresh after deleting", async () => {
    const existing = care({ id: "o1", date: "2026-03-03T00:00:00Z", moisture: "wet" });
    const base = buildJournal({
      attentionProjection: unavailableFicusAttention,
      getOperationsByPlantId: { p1: [operationsPage([existing])] },
      deleteOperationResult: { kind: "deleted" },
    });
    let gardenReads = 0;
    const journal = {
      ...base,
      getPlants: (status?: string) =>
        status === "archived"
          ? Promise.resolve({ kind: "read" as const, plants: [] })
          : gardenReads++ === 0
            ? base.getPlants()
            : Promise.reject(new Error("private details")),
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
    await screen.findByText("3rd of March");

    openDeleteConfirmation();

    const alert = await screen.findByRole("alert");

    expect(alert).toHaveTextContent("The journal could not be loaded.");
    expect(screen.queryByText("private details")).not.toBeInTheDocument();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
});
