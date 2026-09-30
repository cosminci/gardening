import * as Testing from "@solidjs/testing-library";
import * as Vitest from "vitest";
import { App } from "../../src/app/App";
import { instant, pesticideId, pesticideName } from "../../src/domain/Journal";
import type { AttentionProjection } from "../../src/domain/Journal";
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

const openPesticideArchiveConfirmation = () => {
  Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Log operation for Fern" }));
  Testing.fireEvent.click(Testing.screen.getByRole("checkbox", { name: "Pesticide" }));
  Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Edit Neem oil" }));
  Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Archive" }));
  const warning = Testing.screen.getByRole("alertdialog");
  Testing.fireEvent.click(
    Testing.within(warning).getByRole("button", { name: "Archive permanently" }),
  );
};

const openSubstrateArchiveConfirmation = () => {
  Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Log operation for Fern" }));
  Testing.fireEvent.change(Testing.screen.getByRole("combobox", { name: "Operation type" }), {
    target: { value: "repot" },
  });
  Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Edit Perlite" }));
  Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Archive" }));
  const warning = Testing.screen.getByRole("alertdialog");
  Testing.fireEvent.click(
    Testing.within(warning).getByRole("button", { name: "Archive permanently" }),
  );
};

Vitest.describe("catalog archive failures", () => {
  Vitest.it("should report a pesticide archive failure without showing its reason", async () => {
    const cases = [
      {
        makeJournal: () =>
          buildJournal({
            attentionProjection: unavailableFicusAttention,
            getOperationsByPlantId: { p1: [operationsPage()] },
            getPesticidesResult: { kind: "read" as const, entries: pesticides },
            pesticideArchiveResult: { kind: "pesticideMissing" as const },
          }),
        message: "This pesticide no longer exists.",
      },
      {
        makeJournal: () =>
          buildJournal({
            attentionProjection: unavailableFicusAttention,
            getOperationsByPlantId: { p1: [operationsPage()] },
            getPesticidesResult: { kind: "read" as const, entries: pesticides },
            pesticideArchiveResult: { kind: "alreadyArchived" as const },
          }),
        message: "This pesticide was already archived.",
      },
      {
        makeJournal: () =>
          buildJournal({
            attentionProjection: unavailableFicusAttention,
            getOperationsByPlantId: { p1: [operationsPage()] },
            getPesticidesResult: { kind: "read" as const, entries: pesticides },
            pesticideArchiveResult: {
              kind: "archiveFailed" as const,
              reason: new Error("private details"),
            },
          }),
        message: "The pesticide could not be archived.",
      },
      {
        makeJournal: () => ({
          ...buildJournal({
            attentionProjection: unavailableFicusAttention,
            getOperationsByPlantId: { p1: [operationsPage()] },
            getPesticidesResult: { kind: "read" as const, entries: pesticides },
          }),
          archivePesticide: () => Promise.reject(new Error("private details")),
        }),
        message: "The pesticide could not be archived.",
      },
    ];
    for (const { makeJournal, message } of cases) {
      const journal = makeJournal();
      const view = Testing.render(() => (
        <App
          plants={journal}
          operations={journal}
          attention={journal}
          substrates={journal}
          pesticideCatalog={journal}
          photos={noopPhotoClient}
        />
      ));
      await Testing.screen.findByRole("article", { name: "Fern" });

      openPesticideArchiveConfirmation();

      const alert = await Testing.screen.findByRole("alert");

      Vitest.expect(alert).toHaveTextContent(message);
      Vitest.expect(
        Testing.screen.getByRole("dialog", { name: "Pesticide editor" }),
      ).toBeInTheDocument();
      Vitest.expect(Testing.screen.queryByText("private details")).toBeNull();
      view.unmount();
    }
  });

  Vitest.it(
    "should report a substrate component archive failure without showing its reason",
    async () => {
      const cases = [
        {
          makeJournal: () =>
            buildJournal({
              attentionProjection: unavailableFicusAttention,
              getOperationsByPlantId: { p1: [operationsPage()] },
              componentArchiveResult: { kind: "componentMissing" as const },
            }),
          message: "This substrate component no longer exists.",
        },
        {
          makeJournal: () =>
            buildJournal({
              attentionProjection: unavailableFicusAttention,
              getOperationsByPlantId: { p1: [operationsPage()] },
              componentArchiveResult: { kind: "alreadyArchived" as const },
            }),
          message: "This substrate component was already archived.",
        },
        {
          makeJournal: () =>
            buildJournal({
              attentionProjection: unavailableFicusAttention,
              getOperationsByPlantId: { p1: [operationsPage()] },
              componentArchiveResult: {
                kind: "archiveFailed" as const,
                reason: new Error("private details"),
              },
            }),
          message: "The substrate component could not be archived.",
        },
        {
          makeJournal: () => ({
            ...buildJournal({
              attentionProjection: unavailableFicusAttention,
              getOperationsByPlantId: { p1: [operationsPage()] },
            }),
            archiveSubstrateComponent: () => Promise.reject(new Error("private details")),
          }),
          message: "The substrate component could not be archived.",
        },
      ];
      for (const { makeJournal, message } of cases) {
        const journal = makeJournal();
        const view = Testing.render(() => (
          <App
            plants={journal}
            operations={journal}
            attention={journal}
            substrates={journal}
            pesticideCatalog={journal}
            photos={noopPhotoClient}
          />
        ));
        await Testing.screen.findByRole("article", { name: "Fern" });

        openSubstrateArchiveConfirmation();

        const alert = await Testing.screen.findByRole("alert");

        Vitest.expect(alert).toHaveTextContent(message);
        Vitest.expect(
          Testing.screen.getByRole("dialog", { name: "Substrate component editor" }),
        ).toBeInTheDocument();
        Vitest.expect(Testing.screen.queryByText("private details")).toBeNull();
        view.unmount();
      }
    },
  );
});
