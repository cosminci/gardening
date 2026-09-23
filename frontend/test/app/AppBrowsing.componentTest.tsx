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
import type {
  GetAttentionResult,
  GetOperationsResult,
  GetPlantsResult,
  OperationWindow,
  AttentionSample,
  PlantId,
} from "../../src/domain/Journal";
import * as JournalFixtures from "./JournalTestSupport";

const unavailableFicusAttentionResult = {
  kind: "read",
  projection: {
    measuredAt: instant("2026-01-01T00:00:00Z"),
    plants: [
      {
        plantId: JournalFixtures.ficus().id,
        watering: { kind: "unavailable", sampleCount: 0, maybeElapsed: null },
      },
    ],
  },
} satisfies GetAttentionResult;

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
    const browsingAttentionResult: GetAttentionResult = {
      kind: "read",
      projection: {
        measuredAt: instant("2026-01-01T00:00:00Z"),
        plants: [monsteraUnavailableAttention, ficusRedAlertAttention],
      },
    };
    const journal = JournalFixtures.buildJournal({
      getAttentionResults: [browsingAttentionResult],
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

    render(() => <App journal={journal} />);

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
    expect(
      screen.getAllByRole("article").map((article) => article.getAttribute("aria-label")),
    ).toEqual(["Monstera deliciosa", "Fern"]);
    expect(operationWindows).toEqual([
      { plantId: "p2", window: { offset: 0, size: 3 } },
      { plantId: "p1", window: { offset: 0, size: 3 } },
    ]);
    expect(
      within(screen.getByRole("article", { name: "Monstera deliciosa" })).getByText(
        "No operations yet.",
      ),
    ).toBeInTheDocument();

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
    const browsingAttentionResult: GetAttentionResult = {
      kind: "read",
      projection: {
        measuredAt: instant("2026-01-01T00:00:00Z"),
        plants: [
          recentlyWateredCurrentAttention,
          urgentRedAlertAttention,
          unknownUnavailableAttention,
        ],
      },
    };
    const operationWindows: { plantId: PlantId; window: OperationWindow }[] = [];
    const journal = JournalFixtures.buildJournal({
      getAttentionResults: [browsingAttentionResult],
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

    render(() => <App journal={journal} />);

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
      getAttentionResults: [unavailableFicusAttentionResult],
      getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
    });

    render(() => <App journal={journal} />);

    expect(await screen.findByRole("heading", { name: "Plant Journal" })).toBeInTheDocument();
    expect(screen.queryByText("Care history, growing conditions, and repotting notes.")).toBeNull();
    const garden = await screen.findByRole("button", { name: /Garden.*1 plant/ });
    const cemetery = screen.getByRole("button", { name: /Cemetery.*0 plants/ });
    expect(garden).toHaveAttribute("aria-pressed", "true");
    expect(cemetery).toHaveAttribute("aria-pressed", "false");
    cemetery.focus();
    fireEvent.click(cemetery);
    expect(cemetery).toHaveAttribute("aria-pressed", "true");
    expect(garden).toHaveAttribute("aria-pressed", "false");
  });

  it("should restore the cemetery from its URL after refresh and respond to navigation", async () => {
    window.history.replaceState(null, "", "/?from=bookmark#journal");
    const journal = JournalFixtures.buildJournal({
      getAttentionResults: [unavailableFicusAttentionResult],
      getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
    });
    const mounted = render(() => <App journal={journal} />);
    await screen.findByRole("region", { name: "Garden" });

    fireEvent.click(screen.getByRole("button", { name: /Cemetery/ }));
    await screen.findByRole("region", { name: "Cemetery" });
    expect(window.location.search).toBe("?from=bookmark&view=cemetery");
    expect(window.location.hash).toBe("#journal");

    mounted.unmount();
    render(() => <App journal={journal} />);
    expect(await screen.findByRole("region", { name: "Cemetery" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Cemetery/ })).toHaveAttribute(
      "aria-pressed",
      "true",
    );

    window.history.replaceState(null, "", "/?from=bookmark#journal");
    window.dispatchEvent(new PopStateEvent("popstate"));
    expect(await screen.findByRole("region", { name: "Garden" })).toBeInTheDocument();
    expect(window.location.search).toBe("?from=bookmark");
  });

  it("should report an unavailable cemetery when its bookmarked URL cannot load", async () => {
    window.history.replaceState(null, "", "/?view=cemetery");
    const base = JournalFixtures.buildJournal({
      getAttentionResults: [unavailableFicusAttentionResult],
      getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
    });
    const journal = {
      ...base,
      getPlants: (status?: string) =>
        status === "archived" ? Promise.reject(new Error("offline")) : base.getPlants(),
    };

    render(() => <App journal={journal} />);

    expect(await screen.findByRole("alert")).toHaveTextContent("The journal could not be loaded.");
    expect(screen.getByRole("button", { name: /Cemetery/ })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
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
      getAttentionResults: [unavailableFicusAttentionResult],
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

    render(() => <App journal={journal} />);

    const cemetery = await screen.findByRole("button", { name: /Cemetery.*2 plants/ });
    expect(statuses).toEqual([undefined]);
    fireEvent.click(cemetery);
    const card = await screen.findByRole("article", { name: "Monstera deliciosa" });
    expect(screen.getByRole("button", { name: /Cemetery.*1 plant/ })).toBeInTheDocument();
    expect(statuses).toEqual([undefined, "archived"]);
    expect(within(card).getByText("RIP")).toBeInTheDocument();
    expect(within(card).getByText("01.02.2026")).toBeInTheDocument();
    expect(within(card).getByText("03.04.2026")).toBeInTheDocument();
    expect(within(card).queryByRole("button", { name: /Log operation/ })).toBeNull();
    expect(
      within(card).getByRole("button", { name: /Edit recent care operation/ }),
    ).toBeInTheDocument();
    fireEvent.click(within(card).getByRole("button", { name: "Show operation history" }));
    expect(await within(card).findByRole("table")).toHaveTextContent("01.02.2026");
    expect(getOperationDates).toHaveBeenCalledWith(archivedPlant.id);
    fireEvent.click(screen.getByRole("button", { name: /Garden.*1 plant/ }));
    fireEvent.click(screen.getByRole("button", { name: /Cemetery.*1 plant/ }));
    await screen.findByRole("article", { name: "Monstera deliciosa" });
    fireEvent.click(screen.getByRole("button", { name: /Cemetery.*1 plant/ }));
    expect(statuses).toEqual([undefined, "archived", "archived"]);
  });

  it("should fail an archived date read instead of showing a partial cemetery", async () => {
    const archivedPlant = {
      ...JournalFixtures.monstera(),
      details: { ...JournalFixtures.monstera().details, status: "archived" as const },
    };
    const base = JournalFixtures.buildJournal({
      getAttentionResults: [unavailableFicusAttentionResult],
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
    let cemeteryReads = 0;
    const journal = {
      ...base,
      getArchivedCount: () => Promise.resolve({ kind: "read" as const, count: 1 }),
      getPlants: (status?: string) =>
        status === "archived" && cemeteryReads++ === 0
          ? Promise.resolve({ kind: "readFailed" as const, reason: new Error("offline") })
          : Promise.resolve({
              kind: "read" as const,
              plants: status === "archived" ? [archivedPlant] : [JournalFixtures.ficus()],
            }),
      getOperationDates,
    };
    render(() => <App journal={journal} />);
    const cemetery = await screen.findByRole("button", { name: /Cemetery.*1 plant/ });

    fireEvent.click(cemetery);

    expect(await screen.findByRole("alert")).toHaveTextContent("The journal could not be loaded.");
    expect(screen.queryByRole("article", { name: "Monstera deliciosa" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: /Cemetery.*1 plant/ }));
    await vi.waitFor(() => {
      expect(getOperationDates).toHaveBeenCalledOnce();
    });
    expect(await screen.findByRole("alert")).toHaveTextContent("The journal could not be loaded.");
    expect(screen.queryByText("private details")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: /Garden.*1 plant/ }));
    expect(await screen.findByRole("article", { name: "Fern" })).toBeInTheDocument();
  });

  it("should leave a restored garden visible after an obsolete cemetery read rejects", async () => {
    let rejectArchived: (reason: Error) => void = () => undefined;
    const archivedRequest = new Promise<GetPlantsResult>((_resolve, reject) => {
      rejectArchived = reject;
    });
    const base = JournalFixtures.buildJournal({
      getAttentionResults: [unavailableFicusAttentionResult],
      getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
    });
    const journal = {
      ...base,
      getPlants: (status?: string) => (status === "archived" ? archivedRequest : base.getPlants()),
    };
    render(() => <App journal={journal} />);
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
      getAttentionResults: [unavailableFicusAttentionResult],
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
    render(() => <App journal={journal} />);
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
        getAttentionResults: [unavailableFicusAttentionResult],
        getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
      }),
      getArchivedCount: () =>
        Promise.resolve({
          kind: "readFailed" as const,
          reason: new Error("private details"),
        }),
    };

    render(() => <App journal={journal} />);

    expect(await screen.findByRole("alert")).toHaveTextContent("The journal could not be loaded.");
    expect(screen.queryByRole("button", { name: /Cemetery.*0/ })).toBeNull();
  });

  it("should report an attention read failure without showing its reason", async () => {
    const reason = new Error("private details");
    const journal = JournalFixtures.buildJournal({
      getAttentionResults: [{ kind: "readFailed", reason }],
    });

    render(() => <App journal={journal} />);

    expect(await screen.findByRole("alert")).toHaveTextContent("The journal could not be loaded.");
    expect(screen.queryByText("private details")).not.toBeInTheDocument();
  });

  it("should fail the journal load when current plants cannot be read or attention is unmatched", async () => {
    const unrelatedSample = {
      plantId: JournalFixtures.monstera().id,
      watering: { kind: "unavailable" as const, sampleCount: 0, maybeElapsed: null },
    };
    const mismatchedAttention: GetAttentionResult = {
      kind: "read",
      projection: {
        ...unavailableFicusAttentionResult.projection,
        plants: [...unavailableFicusAttentionResult.projection.plants, unrelatedSample],
      },
    };
    const cases = [
      {
        plants: { kind: "readFailed" as const, reason: new Error("private plant details") },
        attention: unavailableFicusAttentionResult,
      },
      {
        plants: { kind: "read" as const, plants: [JournalFixtures.monstera()] },
        attention: unavailableFicusAttentionResult,
      },
      {
        plants: { kind: "read" as const, plants: [JournalFixtures.ficus()] },
        attention: mismatchedAttention,
      },
    ];
    for (const { plants, attention } of cases) {
      const journal = JournalFixtures.buildJournal({
        getAttentionResults: [attention],
        getPlantsResults: [plants],
      });
      const view = render(() => <App journal={journal} />);

      const alert = await screen.findByRole("alert");
      expect(alert).toHaveTextContent("The journal could not be loaded.");
      expect(screen.queryByRole("article")).not.toBeInTheDocument();
      view.unmount();
    }
  });

  it("should report an operation history read failure without showing its reason", async () => {
    const reason = new Error("private details");
    const journal = JournalFixtures.buildJournal({
      getAttentionResults: [unavailableFicusAttentionResult],
      getOperationsByPlantId: { p1: [{ kind: "readFailed", reason }] },
    });

    render(() => <App journal={journal} />);

    expect(await screen.findByRole("alert")).toHaveTextContent("The journal could not be loaded.");
    expect(screen.queryByText("private details")).not.toBeInTheDocument();
  });

  it("should report an unexpected rejected request", async () => {
    const journal = {
      ...JournalFixtures.buildJournal(),
      getAttention: () => Promise.reject(new Error("private details")),
    };

    render(() => <App journal={journal} />);

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

    render(() => <App journal={journal} />);

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

    render(() => <App journal={journal} />);

    expect(await screen.findByRole("alert")).toHaveTextContent("The journal could not be loaded.");
    expect(screen.queryByText("private details")).not.toBeInTheDocument();
  });
});
