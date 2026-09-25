import { fireEvent, render, screen, within } from "@solidjs/testing-library";
import { describe, expect, it, vi } from "vitest";
import { App } from "../../src/app/App";
import {
  instant,
  milliseconds,
  nickname,
  nomenclatureName,
  pesticideId,
  plantId,
} from "../../src/domain/Journal";
import * as Journal from "../../src/domain/Journal";
import type {
  GetOperationsResult,
  GetPlantsResult,
  OperationWindow,
  AttentionSample,
  PlantId,
  NewPlantDetails,
} from "../../src/domain/Journal";
import * as JournalFixtures from "./JournalTestSupport";

const unavailableFicusAttention: Journal.AttentionProjection = {
  measuredAt: instant("2026-01-01T00:00:00Z"),
  plants: [
    {
      plantId: JournalFixtures.ficus().id,
      watering: { kind: "unavailable", sampleCount: 0, maybeElapsed: null },
    },
  ],
};

describe("browsing the journal", () => {
  it("should show each plant with its three most recent operations, oldest first", async () => {
    const neemId = pesticideId("00000000-0000-4000-8001-000000000003");
    const pesticides = new Set([neemId]);
    const actions = new Set(["watered", "pesticide"] as const);
    const treated = JournalFixtures.care({
      id: "o4",
      date: "2026-04-04T22:30:00Z",
      moisture: "wet",
      maybeNote: "Recovered",
      actions,
      pesticides,
    });
    const moderateCare = JournalFixtures.care({
      id: "o2",
      date: "2026-02-02T00:00:00Z",
      moisture: "moderatePlus",
      actions: new Set(),
    });
    const repot = JournalFixtures.repot("o3", "2026-03-03T00:00:00Z");
    const pesticideCatalog = [
      {
        id: neemId,
        data: {
          name: nomenclatureName("Neem oil"),
          pesticideType: "insecticide" as const,
          maybeInfo: null,
        },
      },
    ];
    const operationWindows: {
      plantId: PlantId;
      window: OperationWindow;
    }[] = [];
    const monsteraUnavailableAttention: AttentionSample = {
      plantId: JournalFixtures.monstera().id,
      watering: { kind: "unavailable", sampleCount: 0, maybeElapsed: null },
    };
    const ficusRedAlertAttention: AttentionSample = {
      plantId: JournalFixtures.ficus().id,
      watering: {
        kind: "redAlert",
        sampleCount: 5,
        averageInterval: milliseconds("86400000"),
        elapsed: milliseconds("176400000"),
      },
    };
    const browsingAttention: Journal.AttentionProjection = {
      measuredAt: instant("2026-01-01T00:00:00Z"),
      plants: [monsteraUnavailableAttention, ficusRedAlertAttention],
    };
    const journal = JournalFixtures.buildJournal({
      attentionProjection: browsingAttention,
      getPlantsResults: [
        { kind: "read", plants: [JournalFixtures.monstera(), JournalFixtures.ficus()] },
      ],
      getOperationsByPlantId: {
        p1: [JournalFixtures.operationsPage([treated, repot, moderateCare], true)],
        p2: [JournalFixtures.operationsPage()],
      },
      getPesticidesResult: { kind: "read", entries: pesticideCatalog },
      operationWindows,
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

    const card = await screen.findByRole("article", { name: "Fern" });
    expect(within(card).getByText("Ficus lyrata")).toBeInTheDocument();
    expect(within(card).getByText("Balcony")).toBeInTheDocument();
    expect(within(card).getAllByText("Perlite 100%")).toHaveLength(2);
    expect(within(card).getByRole("list", { name: "Recent operations" })).toBeInTheDocument();
    const renderedOperations = within(card).getAllByRole("listitem");
    expect(renderedOperations.map((operation) => operation.textContent)).toEqual([
      "2nd of FebruaryEditCareMoistureModerate +ActionsNone recorded",
      "3rd of MarchEditRepotSubstratePerlite 100%",
      "5th of AprilEditCareMoistureWetActionsWatered, PesticidePesticidesNeem oilNoteRecovered",
    ]);
    const recentDate = within(card).getByText("5th of April");
    expect(recentDate).toHaveAttribute("datetime", "2026-04-04T22:30:00Z");
    const plantNames = screen
      .getAllByRole("article")
      .map((article) => article.getAttribute("aria-label"));
    expect(plantNames).toEqual(["Monstera deliciosa", "Fern"]);
    expect(operationWindows).toEqual([
      { plantId: "p2", window: { offset: 0, size: 3 } },
      { plantId: "p1", window: { offset: 0, size: 3 } },
    ]);
    const emptyCard = screen.getByRole("article", { name: "Monstera deliciosa" });
    expect(within(emptyCard).getByText("No operations yet.")).toBeInTheDocument();

    fireEvent.click(within(card).getByRole("button", { name: "Show operation history" }));
    const history = await within(card).findByRole("table");
    expect(operationWindows.at(-1)).toEqual({
      plantId: "p1",
      window: { offset: 3, size: 10 },
    });
    fireEvent.click(
      within(history).getByRole("button", {
        name: "Edit historical care operation 1 from 05.04.2026",
      }),
    );
    expect(screen.getByRole("dialog", { name: "Operation editor" })).toBeInTheDocument();
  });

  it("should render backend attention in presentation order", async () => {
    const unknownPlant = {
      ...JournalFixtures.ficus(),
      id: plantId("unknown"),
      details: {
        ...JournalFixtures.ficus().details,
        maybeNickname: nickname("Unknown"),
      },
    };
    const unknownUnavailableAttention: AttentionSample = {
      plantId: unknownPlant.id,
      watering: { kind: "unavailable", sampleCount: 0, maybeElapsed: null },
    };
    const urgentPlant = {
      ...JournalFixtures.ficus(),
      id: plantId("urgent"),
      details: {
        ...JournalFixtures.ficus().details,
        maybeNickname: nickname("Urgent"),
      },
    };
    const urgentRedAlertAttention: AttentionSample = {
      plantId: urgentPlant.id,
      watering: {
        kind: "redAlert",
        sampleCount: 5,
        averageInterval: milliseconds("0"),
        elapsed: milliseconds("1"),
      },
    };
    const recentlyWateredPlant = {
      ...JournalFixtures.ficus(),
      id: plantId("current"),
      details: {
        ...JournalFixtures.ficus().details,
        maybeNickname: nickname("Current"),
      },
    };
    const recentlyWateredCurrentAttention: AttentionSample = {
      plantId: recentlyWateredPlant.id,
      watering: {
        kind: "current",
        sampleCount: 5,
        averageInterval: milliseconds("10"),
        elapsed: milliseconds("1"),
      },
    };
    const browsingAttention: Journal.AttentionProjection = {
      measuredAt: instant("2026-01-01T00:00:00Z"),
      plants: [
        recentlyWateredCurrentAttention,
        urgentRedAlertAttention,
        unknownUnavailableAttention,
      ],
    };
    const operationWindows: { plantId: PlantId; window: OperationWindow }[] = [];
    const journal = JournalFixtures.buildJournal({
      attentionProjection: browsingAttention,
      getPlantsResults: [
        { kind: "read", plants: [unknownPlant, urgentPlant, recentlyWateredPlant] },
      ],
      getOperationsByPlantId: Object.fromEntries(
        [unknownPlant, urgentPlant, recentlyWateredPlant].map(({ id }) => [
          id,
          [JournalFixtures.operationsPage()],
        ]),
      ),
      operationWindows,
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

    const articles = await screen.findAllByRole("article");
    expect(articles.map((article) => article.getAttribute("aria-label"))).toEqual([
      "Unknown",
      "Urgent",
      "Current",
    ]);
    expect(operationWindows.map(({ plantId: id }) => id)).toEqual(["unknown", "urgent", "current"]);
  });

  it("should identify a journal containing one active plant", async () => {
    const journal = JournalFixtures.buildJournal({
      attentionProjection: unavailableFicusAttention,
      getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
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

    const heading = await screen.findByRole("heading", { name: "Plant Journal" });
    const garden = await screen.findByRole("button", { name: /Garden.*1 plant/ });
    const cemetery = screen.getByRole("button", { name: /Cemetery.*0 plants/ });

    expect(heading).toBeInTheDocument();
    expect(screen.queryByText("Care history, growing conditions, and repotting notes.")).toBeNull();
    expect(garden).toHaveAttribute("aria-pressed", "true");
    expect(cemetery).toHaveAttribute("aria-pressed", "false");
  });

  it("should select the cemetery from its focusable control", async () => {
    const journal = JournalFixtures.buildJournal({
      attentionProjection: unavailableFicusAttention,
      getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
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
    const cemetery = await screen.findByRole("button", { name: /Cemetery.*0 plants/ });

    cemetery.focus();
    fireEvent.click(cemetery);
    const cemeteryView = await screen.findByRole("region", { name: "Cemetery" });
    const garden = screen.getByRole("button", { name: /Garden.*1 plant/ });

    expect(cemeteryView).toBeInTheDocument();
    expect(cemetery).toHaveFocus();
    expect(cemetery).toHaveAttribute("aria-pressed", "true");
    expect(garden).toHaveAttribute("aria-pressed", "false");
  });

  it("should restore the cemetery from its URL after refresh and respond to navigation", async () => {
    window.history.replaceState(null, "", "/?from=bookmark#journal");
    const journal = JournalFixtures.buildJournal({
      attentionProjection: unavailableFicusAttention,
      getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
    });
    const mounted = render(() => (
      <App
        plants={journal}
        operations={journal}
        attention={journal}
        substrates={journal}
        pesticideCatalog={journal}
      />
    ));
    await screen.findByRole("region", { name: "Garden" });

    fireEvent.click(screen.getByRole("button", { name: /Cemetery/ }));
    await screen.findByRole("region", { name: "Cemetery" });
    const cemeterySearch = window.location.search;
    const cemeteryHash = window.location.hash;

    mounted.unmount();
    render(() => (
      <App
        plants={journal}
        operations={journal}
        attention={journal}
        substrates={journal}
        pesticideCatalog={journal}
      />
    ));
    const restoredCemetery = await screen.findByRole("region", { name: "Cemetery" });
    const selectedCemetery = screen.getByRole("button", { name: /Cemetery/ });
    const restoredCemeteryLabel = restoredCemetery.getAttribute("aria-label");
    const cemeterySelected = selectedCemetery.getAttribute("aria-pressed");

    window.history.replaceState(null, "", "/?from=bookmark#journal");
    window.dispatchEvent(new PopStateEvent("popstate"));
    const restoredGarden = await screen.findByRole("region", { name: "Garden" });

    expect(cemeterySearch).toBe("?from=bookmark&view=cemetery");
    expect(cemeteryHash).toBe("#journal");
    expect(restoredCemeteryLabel).toBe("Cemetery");
    expect(cemeterySelected).toBe("true");
    expect(restoredGarden).toBeInTheDocument();
    expect(window.location.search).toBe("?from=bookmark");
  });

  it("should report an unavailable cemetery when its bookmarked URL cannot load", async () => {
    window.history.replaceState(null, "", "/?view=cemetery");
    const base = JournalFixtures.buildJournal({
      attentionProjection: unavailableFicusAttention,
      getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
    });
    const journal = {
      ...base,
      getPlants: (status?: string) =>
        status === "archived" ? Promise.reject(new Error("offline")) : base.getPlants(),
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

    expect(await screen.findByRole("alert")).toHaveTextContent("The journal could not be loaded.");
    const selectedCemetery = screen.getByRole("button", { name: /Cemetery/ });
    expect(selectedCemetery).toHaveAttribute("aria-pressed", "true");
    expect(screen.queryByRole("article", { name: "Fern" })).toBeNull();
  });

  it("should load archived plants only when the cemetery is opened", async () => {
    const archivedPlant = {
      ...JournalFixtures.monstera(),
      details: { ...JournalFixtures.monstera().details, status: "archived" as const },
    };
    const firstOperation = {
      ...JournalFixtures.care({ id: "first", date: "2026-02-01T10:00:00Z", moisture: "dry" }),
      plantId: archivedPlant.id,
    };
    const latestOperation = {
      ...JournalFixtures.care({ id: "last", date: "2026-04-03T18:00:00Z", moisture: "wet" }),
      plantId: archivedPlant.id,
    };
    const base = JournalFixtures.buildJournal({
      attentionProjection: unavailableFicusAttention,
      getOperationsByPlantId: {
        p1: [JournalFixtures.operationsPage()],
        p2: [
          JournalFixtures.operationsPage([latestOperation], true),
          JournalFixtures.operationsPage([firstOperation]),
        ],
      },
    });
    const statuses: (string | undefined)[] = [];
    const getOperationDates = vi.fn(() =>
      Promise.resolve({
        kind: "read" as const,
        dates: {
          kind: "recorded" as const,
          first: firstOperation.date,
          last: latestOperation.date,
        },
      }),
    );
    const journal = {
      ...base,
      getPlants: (status?: string) => {
        statuses.push(status);
        return Promise.resolve({
          kind: "read" as const,
          plants: status === "archived" ? [archivedPlant] : [JournalFixtures.ficus()],
        });
      },
      getArchivedCount: vi.fn(() => Promise.resolve({ kind: "read" as const, count: 2 })),
      getOperationDates,
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

    const cemetery = await screen.findByRole("button", { name: /Cemetery.*2 plants/ });
    const unopenedStatuses = [...statuses];
    fireEvent.click(cemetery);
    const card = await screen.findByRole("article", { name: "Monstera deliciosa" });
    fireEvent.click(within(card).getByRole("button", { name: "Show operation history" }));
    const history = await within(card).findByRole("table");

    const life = within(card).getByRole("complementary", { name: /Recorded care dates/ });
    const editOperation = within(card).getByRole("button", { name: /Edit recent care operation/ });

    expect(unopenedStatuses).toEqual([undefined]);
    expect(screen.getByRole("button", { name: /Cemetery.*1 plant/ })).toBeInTheDocument();
    expect(within(life).getByText("RIP")).toBeInTheDocument();
    expect(within(life).getByText("01.02.2026")).toBeInTheDocument();
    expect(within(life).getByText("03.04.2026")).toBeInTheDocument();
    expect(within(card).queryByRole("button", { name: /Log operation/ })).toBeNull();
    expect(editOperation).toBeInTheDocument();
    expect(history).toHaveTextContent("01.02.2026");
    expect(getOperationDates).toHaveBeenCalledWith(archivedPlant.id);
    expect(statuses).toEqual([undefined, "archived"]);
  });

  it("should refresh the cemetery list when returning from the garden", async () => {
    const archivedPlant = {
      ...JournalFixtures.monstera(),
      details: { ...JournalFixtures.monstera().details, status: "archived" as const },
    };
    const base = JournalFixtures.buildJournal({
      attentionProjection: unavailableFicusAttention,
      getOperationsByPlantId: {
        p1: [JournalFixtures.operationsPage()],
        p2: [JournalFixtures.operationsPage()],
      },
    });
    const statuses: (string | undefined)[] = [];
    const journal = {
      ...base,
      getArchivedCount: () => Promise.resolve({ kind: "read" as const, count: 1 }),
      getPlants: (status?: string) => {
        statuses.push(status);
        return Promise.resolve({
          kind: "read" as const,
          plants: status === "archived" ? [archivedPlant] : [JournalFixtures.ficus()],
        });
      },
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

    fireEvent.click(screen.getByRole("button", { name: /Cemetery.*1 plant/ }));
    await screen.findByRole("article", { name: "Monstera deliciosa" });
    fireEvent.click(screen.getByRole("button", { name: /Garden.*1 plant/ }));
    fireEvent.click(screen.getByRole("button", { name: /Cemetery.*1 plant/ }));
    const cemeteryCard = await screen.findByRole("article", { name: "Monstera deliciosa" });
    const readsBeforeReselect = statuses.length;
    fireEvent.click(screen.getByRole("button", { name: /Cemetery.*1 plant/ }));

    expect(cemeteryCard).toBeInTheDocument();
    expect(statuses).toEqual([undefined, "archived", "archived"]);
    expect(statuses).toHaveLength(readsBeforeReselect);
  });

  it("should fail an archived date read instead of showing a partial cemetery", async () => {
    const archivedPlant = {
      ...JournalFixtures.monstera(),
      details: { ...JournalFixtures.monstera().details, status: "archived" as const },
    };
    const base = JournalFixtures.buildJournal({
      attentionProjection: unavailableFicusAttention,
      getOperationsByPlantId: {
        p1: [JournalFixtures.operationsPage()],
        p2: [JournalFixtures.operationsPage()],
      },
    });
    const getOperationDates = vi.fn(() =>
      Promise.resolve({
        kind: "readFailed" as const,
        reason: new Error("private details"),
      }),
    );
    const journal = {
      ...base,
      getArchivedCount: () => Promise.resolve({ kind: "read" as const, count: 1 }),
      getPlants: (status?: string) =>
        Promise.resolve({
          kind: "read" as const,
          plants: status === "archived" ? [archivedPlant] : [JournalFixtures.ficus()],
        }),
      getOperationDates,
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
    const cemetery = await screen.findByRole("button", { name: /Cemetery.*1 plant/ });

    fireEvent.click(cemetery);
    const alert = await screen.findByRole("alert");
    const incompleteCard = screen.queryByRole("article", { name: "Monstera deliciosa" });
    fireEvent.click(screen.getByRole("button", { name: /Garden.*1 plant/ }));
    const gardenCard = await screen.findByRole("article", { name: "Fern" });

    expect(alert).toHaveTextContent("The journal could not be loaded.");
    expect(incompleteCard).toBeNull();
    expect(getOperationDates).toHaveBeenCalledWith(archivedPlant.id);
    expect(screen.queryByText("private details")).toBeNull();
    expect(gardenCard).toBeInTheDocument();
  });

  it("should report an unreadable cemetery list rather than showing partial cards", async () => {
    const base = JournalFixtures.buildJournal({
      attentionProjection: unavailableFicusAttention,
      getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
    });
    const journal = {
      ...base,
      getPlants: (status?: string) =>
        status === "archived"
          ? Promise.resolve({ kind: "readFailed" as const, reason: new Error("offline") })
          : base.getPlants(),
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
    const cemetery = await screen.findByRole("button", { name: /Cemetery.*0 plants/ });

    fireEvent.click(cemetery);
    const alert = await screen.findByRole("alert");

    expect(alert).toHaveTextContent("The journal could not be loaded.");
    expect(screen.queryByRole("article", { name: "Fern" })).toBeNull();
  });

  it("should leave a restored garden visible after an obsolete cemetery read rejects", async () => {
    let rejectArchived: (reason: Error) => void = () => undefined;
    const archivedRequest = new Promise<GetPlantsResult>((_resolve, reject) => {
      rejectArchived = reject;
    });
    const base = JournalFixtures.buildJournal({
      attentionProjection: unavailableFicusAttention,
      getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
    });
    const journal = {
      ...base,
      getPlants: (status?: string) => (status === "archived" ? archivedRequest : base.getPlants()),
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

    fireEvent.click(screen.getByRole("button", { name: /Cemetery.*0 plants/ }));
    fireEvent.click(screen.getByRole("button", { name: /Garden.*1 plant/ }));
    rejectArchived(new Error("stale request"));
    await archivedRequest.catch(() => undefined);
    await Promise.resolve();

    expect(screen.getByRole("article", { name: "Fern" })).toBeInTheDocument();
    expect(screen.queryByRole("alert")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: /Cemetery.*0 plants/ }));
    expect(await screen.findByRole("alert")).toHaveTextContent("The journal could not be loaded.");
  });

  it("should ignore obsolete cemetery list and history responses after returning to the garden", async () => {
    const archivedPlant = {
      ...JournalFixtures.monstera(),
      details: { ...JournalFixtures.monstera().details, status: "archived" as const },
    };
    let finishList: (result: GetPlantsResult) => void = () => undefined;
    const pendingList = new Promise<GetPlantsResult>((resolve) => {
      finishList = resolve;
    });
    let finishHistory: (result: GetOperationsResult) => void = () => undefined;
    const pendingHistory = new Promise<GetOperationsResult>((resolve) => {
      finishHistory = resolve;
    });
    const base = JournalFixtures.buildJournal({
      attentionProjection: unavailableFicusAttention,
      getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
    });
    const getOperationDates = vi.fn(() =>
      Promise.resolve({ kind: "read" as const, dates: { kind: "empty" as const } }),
    );
    let cemeteryReads = 0;
    const journal = {
      ...base,
      getPlants: (status?: string) =>
        status !== "archived"
          ? base.getPlants()
          : cemeteryReads++ === 0
            ? pendingList
            : Promise.resolve({ kind: "read" as const, plants: [archivedPlant] }),
      getOperationDates,
      getOperations: (id: PlantId, window: OperationWindow) =>
        id === archivedPlant.id ? pendingHistory : base.getOperations(id, window),
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

    fireEvent.click(screen.getByRole("button", { name: /Cemetery.*0 plants/ }));
    fireEvent.click(screen.getByRole("button", { name: /Garden.*1 plant/ }));
    finishList({ kind: "read", plants: [archivedPlant] });
    await pendingList;
    fireEvent.click(screen.getByRole("button", { name: /Cemetery.*0 plants/ }));
    await vi.waitFor(() => {
      expect(getOperationDates).toHaveBeenCalledOnce();
    });
    fireEvent.click(screen.getByRole("button", { name: /Garden.*1 plant/ }));
    finishHistory(JournalFixtures.operationsPage());
    await pendingHistory;
    await Promise.resolve();

    expect(screen.getByRole("article", { name: "Fern" })).toBeInTheDocument();
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("should show a failed archived count read instead of a guessed number", async () => {
    const journal = {
      ...JournalFixtures.buildJournal({
        attentionProjection: unavailableFicusAttention,
        getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
      }),
      getArchivedCount: () =>
        Promise.resolve({
          kind: "readFailed" as const,
          reason: new Error("private details"),
        }),
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

    expect(await screen.findByRole("alert")).toHaveTextContent("The journal could not be loaded.");
    expect(screen.queryByRole("button", { name: /Cemetery.*0/ })).toBeNull();
  });

  it("should show a pending indicator for an active plant not in the received attention projection", async () => {
    const missing = JournalFixtures.monstera();
    const journal = JournalFixtures.buildJournal({
      attentionProjection: unavailableFicusAttention,
      getPlantsResults: [{ kind: "read", plants: [JournalFixtures.ficus(), missing] }],
      getOperationsByPlantId: {
        p1: [JournalFixtures.operationsPage()],
        p2: [JournalFixtures.operationsPage()],
      },
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

    const newCard = await screen.findByRole("article", { name: "Monstera deliciosa" });
    expect(
      within(newCard).getByRole("complementary", {
        name: "Attention pending for Monstera deliciosa",
      }),
    ).toBeInTheDocument();
    expect(within(newCard).getByText("No operations yet.")).toBeInTheDocument();
  });

  it("should show the attention feed connection state indicator", async () => {
    const journal = JournalFixtures.buildJournal({
      attentionProjection: unavailableFicusAttention,
      getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
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
    const indicator = screen.getByRole("status", { name: "Backend connected" });
    expect(indicator).toBeInTheDocument();
    expect(screen.getByText(/Updated \d+s ago/)).toBeInTheDocument();

    journal.setAttentionConnection("disconnected");
    expect(await screen.findByRole("status", { name: "Backend disconnected" })).toBeInTheDocument();
  });

  it("should create a plant from the cemetery without logging care or measuring attention", async () => {
    const added = JournalFixtures.monstera();
    const base = JournalFixtures.buildJournal({
      attentionProjection: unavailableFicusAttention,
      getPlantsResults: [
        { kind: "read", plants: [JournalFixtures.ficus()] },
        { kind: "read", plants: [JournalFixtures.ficus(), added] },
      ],
      getOperationsByPlantId: {
        p1: [JournalFixtures.operationsPage()],
        p2: [JournalFixtures.operationsPage()],
      },
    });
    const createPlant = vi
      .fn<(_details: NewPlantDetails) => Promise<Journal.CreatePlantResult>>()
      .mockResolvedValue({ kind: "created", plant: added });
    const logOperation = vi.fn(
      (id: PlantId, date: Journal.Instant, details: Journal.OperationDetails) =>
        base.logOperation(id, date, details),
    );
    const journal = {
      ...base,
      createPlant,
      logOperation,
      getPlants: (status?: "active" | "archived") =>
        status === "archived"
          ? Promise.resolve({ kind: "read" as const, plants: [] })
          : base.getPlants(),
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
    const addPlant = screen.getByRole("button", { name: "Add plant" });
    expect(addPlant.nextElementSibling).toHaveAttribute("aria-label", "Plant views");
    fireEvent.click(screen.getByRole("button", { name: /Cemetery.*0 plants/ }));
    fireEvent.click(screen.getByRole("button", { name: "Add plant" }));
    const dialog = screen.getByRole("dialog", { name: "Plant editor" });
    fireEvent.input(within(dialog).getByRole("textbox", { name: "Species" }), {
      target: { value: "  Monstera deliciosa  " },
    });
    fireEvent.input(within(dialog).getByRole("textbox", { name: "Location" }), {
      target: { value: " Kitchen " },
    });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save plant" }));

    const newCard = await screen.findByRole("article", { name: "Monstera deliciosa" });
    expect(
      within(newCard).getByRole("complementary", {
        name: "Attention pending for Monstera deliciosa",
      }),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Garden.*2 plants/ })).toHaveFocus();
    expect(createPlant).toHaveBeenCalledOnce();
    expect(createPlant.mock.calls[0]?.[0]).toMatchObject({
      species: "Monstera deliciosa",
      location: "Kitchen",
      substrate: [{ share: 100 }],
    });
    expect(logOperation).not.toHaveBeenCalled();
  });

  it("should keep invalid and failed plant creation editable without duplicating a saved plant", async () => {
    const added = JournalFixtures.monstera();
    const base = JournalFixtures.buildJournal({
      attentionProjection: unavailableFicusAttention,
      getPlantsResults: [
        { kind: "read", plants: [JournalFixtures.ficus()] },
        { kind: "readFailed", reason: new Error("offline") },
      ],
      getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
    });
    const createPlant = vi
      .fn()
      .mockResolvedValueOnce({ kind: "createFailed", reason: new Error("offline") })
      .mockResolvedValueOnce({ kind: "created", plant: added });
    const journal = { ...base, createPlant };

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
    fireEvent.click(screen.getByRole("button", { name: "Add plant" }));
    const dialog = screen.getByRole("dialog", { name: "Plant editor" });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save plant" }));
    expect(within(dialog).getByRole("alert")).toHaveTextContent("Enter a species.");
    expect(createPlant).not.toHaveBeenCalled();
    fireEvent.input(within(dialog).getByRole("textbox", { name: "Species" }), {
      target: { value: "Monstera deliciosa" },
    });
    fireEvent.input(within(dialog).getByRole("textbox", { name: "Location" }), {
      target: { value: "Kitchen" },
    });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save plant" }));
    expect(await within(dialog).findByText("The plant could not be saved.")).toBeInTheDocument();
    expect(within(dialog).getByRole("textbox", { name: "Species" })).toHaveValue(
      "Monstera deliciosa",
    );
    fireEvent.click(within(dialog).getByRole("button", { name: "Save plant" }));

    await vi.waitFor(() => {
      expect(screen.getByRole("alert")).toHaveTextContent(
        "Plant was saved, but the garden could not be loaded.",
      );
    });
    expect(screen.queryByRole("dialog", { name: "Plant editor" })).not.toBeInTheDocument();
    expect(createPlant).toHaveBeenCalledTimes(2);
    expect(screen.getByRole("button", { name: /Garden.*1 plant/ })).toHaveFocus();
  });

  it.each([
    [{ kind: "unknownComponent" as const }, "Choose known substrate components."],
    [
      { kind: "catalogReadFailed" as const, reason: new Error("private catalog") },
      "The substrate catalog could not be read. Try again.",
    ],
    [new Error("private connection"), "The plant could not be saved."],
  ])("should keep the plant sheet open after creation cannot proceed", async (outcome, message) => {
    const base = JournalFixtures.buildJournal({
      attentionProjection: unavailableFicusAttention,
      getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
    });
    const journal = {
      ...base,
      createPlant: () =>
        outcome instanceof Error ? Promise.reject(outcome) : Promise.resolve(outcome),
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
    fireEvent.click(screen.getByRole("button", { name: "Add plant" }));
    const dialog = screen.getByRole("dialog", { name: "Plant editor" });
    fireEvent.input(within(dialog).getByRole("textbox", { name: "Species" }), {
      target: { value: "Aloe" },
    });
    fireEvent.input(within(dialog).getByRole("textbox", { name: "Location" }), {
      target: { value: "Office" },
    });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save plant" }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent(message);
    expect(within(dialog).getByRole("textbox", { name: "Species" })).toHaveValue("Aloe");
    expect(dialog).toHaveFocus();
    expect(screen.queryByText("private connection")).not.toBeInTheDocument();
  });

  it("should cancel plant creation without a write and return focus to Add plant", async () => {
    const base = JournalFixtures.buildJournal({
      attentionProjection: unavailableFicusAttention,
      getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
    });
    const createPlant = vi.fn((details: NewPlantDetails) => base.createPlant(details));
    const journal = { ...base, createPlant };
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
    fireEvent.click(screen.getByRole("button", { name: "Add plant" }));
    fireEvent.click(screen.getByRole("button", { name: "Collapse plant editor" }));

    await vi.waitFor(() => {
      expect(screen.queryByRole("dialog", { name: "Plant editor" })).toBeNull();
    });
    expect(screen.getByRole("button", { name: "Add plant" })).toHaveFocus();
    expect(createPlant).not.toHaveBeenCalled();
  });

  it("should edit an active plant's details without logging an operation and refocus the edit control", async () => {
    const base = JournalFixtures.buildJournal({
      attentionProjection: unavailableFicusAttention,
      getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
    });
    const editPlant = vi
      .fn<(_id: PlantId, _details: NewPlantDetails) => Promise<Journal.EditPlantResult>>()
      .mockResolvedValue({ kind: "edited" });
    const journal = { ...base, editPlant };
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
    fireEvent.click(screen.getByRole("button", { name: "Edit Fern" }));
    const dialog = screen.getByRole("dialog", { name: "Plant editor" });
    expect(within(dialog).getByRole("heading", { name: "Edit plant" })).toBeInTheDocument();
    expect(within(dialog).getByRole("textbox", { name: "Species" })).toHaveValue("Ficus lyrata");
    fireEvent.input(within(dialog).getByRole("textbox", { name: "Location" }), {
      target: { value: "Living room" },
    });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));

    await vi.waitFor(() => {
      expect(screen.queryByRole("dialog", { name: "Plant editor" })).toBeNull();
    });
    expect(editPlant).toHaveBeenCalledOnce();
    expect(editPlant.mock.calls[0]?.[0]).toEqual(JournalFixtures.ficus().id);
    expect(editPlant.mock.calls[0]?.[1]).toMatchObject({ location: "Living room" });
    expect(screen.getByRole("button", { name: "Edit Fern" })).toHaveFocus();
  });

  it("should report an edited plant whose garden reload rejects", async () => {
    const base = JournalFixtures.buildJournal({
      attentionProjection: unavailableFicusAttention,
      getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
    });
    let gardenReads = 0;
    const journal = {
      ...base,
      getPlants: (status?: string) =>
        status === "archived"
          ? base.getPlants()
          : gardenReads++ === 0
            ? base.getPlants()
            : Promise.reject(new Error("private details")),
      editPlant: () => Promise.resolve({ kind: "edited" as const }),
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
    fireEvent.click(screen.getByRole("button", { name: "Edit Fern" }));
    const dialog = screen.getByRole("dialog", { name: "Plant editor" });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));

    await vi.waitFor(() => {
      expect(screen.getByRole("alert")).toHaveTextContent("The journal could not be loaded.");
    });
    expect(screen.queryByText("private details")).not.toBeInTheDocument();
  });

  it.each([
    [{ kind: "plantMissing" as const }, "This plant no longer exists."],
    [{ kind: "plantArchived" as const }, "This plant is archived and can no longer be edited."],
    [{ kind: "unknownComponent" as const }, "Choose known substrate components."],
    [
      { kind: "catalogReadFailed" as const, reason: new Error("private catalog") },
      "The substrate catalog could not be read. Try again.",
    ],
    [
      { kind: "editFailed" as const, reason: new Error("private write") },
      "The plant could not be saved.",
    ],
    [new Error("private connection"), "The plant could not be saved."],
  ])("should keep the plant sheet open when editing reports %o", async (outcome, message) => {
    const base = JournalFixtures.buildJournal({
      attentionProjection: unavailableFicusAttention,
      getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
    });
    const journal = {
      ...base,
      editPlant: () =>
        outcome instanceof Error ? Promise.reject(outcome) : Promise.resolve(outcome),
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
    fireEvent.click(screen.getByRole("button", { name: "Edit Fern" }));
    const dialog = screen.getByRole("dialog", { name: "Plant editor" });

    fireEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent(message);
  });

  it("should cancel editing without a write and return focus to the edit control", async () => {
    const base = JournalFixtures.buildJournal({
      attentionProjection: unavailableFicusAttention,
      getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
    });
    const editPlant = vi.fn((id: PlantId, details: NewPlantDetails) => base.editPlant(id, details));
    const journal = { ...base, editPlant };
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
    fireEvent.click(screen.getByRole("button", { name: "Edit Fern" }));
    fireEvent.click(screen.getByRole("button", { name: "Collapse plant editor" }));

    await vi.waitFor(() => {
      expect(screen.queryByRole("dialog", { name: "Plant editor" })).toBeNull();
    });
    expect(screen.getByRole("button", { name: "Edit Fern" })).toHaveFocus();
    expect(editPlant).not.toHaveBeenCalled();
  });

  it("should fail the journal load when current plants cannot be read or pushed attention is unmatched", async () => {
    const unrelatedSample = {
      plantId: JournalFixtures.monstera().id,
      watering: { kind: "unavailable" as const, sampleCount: 0, maybeElapsed: null },
    };
    const mismatchedAttention: Journal.AttentionProjection = {
      ...unavailableFicusAttention,
      plants: [...unavailableFicusAttention.plants, unrelatedSample],
    };
    const ficusSample: Journal.AttentionSample = {
      plantId: JournalFixtures.ficus().id,
      watering: { kind: "unavailable", sampleCount: 0, maybeElapsed: null },
    };
    const duplicateAttention: Journal.AttentionProjection = {
      ...unavailableFicusAttention,
      plants: [ficusSample, ficusSample],
    };
    const cases = [
      {
        plants: { kind: "readFailed" as const, reason: new Error("private plant details") },
        attentionProjection: unavailableFicusAttention,
      },
      {
        plants: { kind: "read" as const, plants: [JournalFixtures.ficus()] },
        attentionProjection: mismatchedAttention,
      },
      {
        plants: { kind: "read" as const, plants: [JournalFixtures.ficus()] },
        attentionProjection: duplicateAttention,
      },
    ];
    for (const { plants, attentionProjection } of cases) {
      const journal = JournalFixtures.buildJournal({
        attentionProjection,
        getPlantsResults: [plants],
      });
      const view = render(() => (
        <App
          plants={journal}
          operations={journal}
          attention={journal}
          substrates={journal}
          pesticideCatalog={journal}
        />
      ));

      const alert = await screen.findByRole("alert");
      expect(alert).toHaveTextContent("The journal could not be loaded.");
      expect(screen.queryByRole("article")).not.toBeInTheDocument();
      view.unmount();
    }
  });

  it("should fail when a pushed projection contains a plant outside the active set", async () => {
    const ficusPlant = JournalFixtures.ficus();
    const monsteraPlant = JournalFixtures.monstera();
    const ficusOnlyProjection: Journal.AttentionProjection = {
      measuredAt: Journal.instant("2026-01-01T00:00:00Z"),
      plants: [
        {
          plantId: ficusPlant.id,
          watering: { kind: "unavailable" as const, sampleCount: 0, maybeElapsed: null },
        },
      ],
    };
    const journal = JournalFixtures.buildJournal({
      attentionProjection: ficusOnlyProjection,
      getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
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

    journal.pushAttention({
      measuredAt: Journal.instant("2026-01-02T00:00:00Z"),
      plants: [
        {
          plantId: ficusPlant.id,
          watering: { kind: "unavailable" as const, sampleCount: 0, maybeElapsed: null },
        },
        {
          plantId: monsteraPlant.id,
          watering: { kind: "unavailable" as const, sampleCount: 0, maybeElapsed: null },
        },
      ],
    });

    expect(await screen.findByRole("alert")).toHaveTextContent("The journal could not be loaded.");
  });

  it("should remove archived plant ids from the recently-archived set when a projection omits them", async () => {
    const ficusPlant = JournalFixtures.ficus();
    const base = JournalFixtures.buildJournal({
      attentionProjection: unavailableFicusAttention,
      getPlantsResults: [
        { kind: "read", plants: [ficusPlant] },
        { kind: "read", plants: [] },
      ],
      getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
    });
    const journal = {
      ...base,
      getArchivedCount: () => Promise.resolve({ kind: "read" as const, count: 1 }),
      archivePlant: () => Promise.resolve({ kind: "archived" as const }),
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

    fireEvent.click(screen.getByRole("button", { name: "Archive Fern" }));
    fireEvent.click(screen.getByRole("button", { name: "Archive permanently" }));
    await screen.findByRole("button", { name: /Garden.*0 plants/ });

    journal.pushAttention({ measuredAt: Journal.instant("2026-01-02T00:00:00Z"), plants: [] });

    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("should report an operation history read failure without showing its reason", async () => {
    const reason = new Error("private details");
    const journal = JournalFixtures.buildJournal({
      attentionProjection: unavailableFicusAttention,
      getOperationsByPlantId: { p1: [{ kind: "readFailed", reason }] },
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

    expect(await screen.findByRole("alert")).toHaveTextContent("The journal could not be loaded.");
    expect(screen.queryByText("private details")).not.toBeInTheDocument();
  });

  it("should report an unexpected rejected request", async () => {
    const base = JournalFixtures.buildJournal();
    const journal = {
      ...base,
      getPlants: () => Promise.reject(new Error("private details")),
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

    expect(await screen.findByRole("alert")).toHaveTextContent("The journal could not be loaded.");
    expect(screen.queryByText("private details")).not.toBeInTheDocument();
  });

  it("should report substrate catalog failures", async () => {
    const journal = JournalFixtures.buildJournal({
      getSubstrateComponentsResult: {
        kind: "readFailed",
        reason: new Error("private details"),
      },
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

    expect(await screen.findByRole("alert")).toHaveTextContent("The journal could not be loaded.");
    expect(screen.queryByText("private details")).not.toBeInTheDocument();
  });

  it("should report pesticide catalog failures", async () => {
    const journal = JournalFixtures.buildJournal({
      getPesticidesResult: {
        kind: "readFailed",
        reason: new Error("private details"),
      },
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

    expect(await screen.findByRole("alert")).toHaveTextContent("The journal could not be loaded.");
    expect(screen.queryByText("private details")).not.toBeInTheDocument();
  });

  it("should report a saved plant whose garden reload rejects", async () => {
    const added = JournalFixtures.monstera();
    const base = JournalFixtures.buildJournal({
      attentionProjection: unavailableFicusAttention,
      getPlantsResults: [{ kind: "read", plants: [JournalFixtures.ficus()] }],
      getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
    });
    let gardenReads = 0;
    const journal = {
      ...base,
      getPlants: (status?: string) =>
        status === "archived"
          ? base.getPlants()
          : gardenReads++ === 0
            ? base.getPlants()
            : Promise.reject(new Error("private details")),
      createPlant: () => Promise.resolve({ kind: "created" as const, plant: added }),
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

    fireEvent.click(screen.getByRole("button", { name: "Add plant" }));
    const dialog = screen.getByRole("dialog", { name: "Plant editor" });
    fireEvent.input(within(dialog).getByRole("textbox", { name: "Species" }), {
      target: { value: "Monstera deliciosa" },
    });
    fireEvent.input(within(dialog).getByRole("textbox", { name: "Location" }), {
      target: { value: "Kitchen" },
    });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save plant" }));

    await vi.waitFor(() => {
      expect(screen.getByRole("alert")).toHaveTextContent(
        "Plant was saved, but the garden could not be loaded.",
      );
    });
    expect(screen.queryByText("private details")).not.toBeInTheDocument();
  });

  it("should ignore a stale cemetery bookmark rejection after switching to the garden", async () => {
    window.history.replaceState(null, "", "/?view=cemetery");
    let rejectArchived: (reason: Error) => void = () => undefined;
    const archivedRequest = new Promise<GetPlantsResult>((_resolve, reject) => {
      rejectArchived = reject;
    });
    const base = JournalFixtures.buildJournal({
      attentionProjection: unavailableFicusAttention,
      getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
    });
    const journal = {
      ...base,
      getPlants: (status?: string) => (status === "archived" ? archivedRequest : base.getPlants()),
    };
    try {
      render(() => (
        <App
          plants={journal}
          operations={journal}
          attention={journal}
          substrates={journal}
          pesticideCatalog={journal}
        />
      ));

      const garden = await screen.findByRole("button", { name: /Garden.*1 plant/ });
      fireEvent.click(garden);
      rejectArchived(new Error("stale bookmark"));
      await archivedRequest.catch(() => undefined);
      await Promise.resolve();

      expect(screen.getByRole("article", { name: "Fern" })).toBeInTheDocument();
      expect(screen.queryByRole("alert")).toBeNull();
    } finally {
      window.history.replaceState(null, "", "/");
    }
  });
});
