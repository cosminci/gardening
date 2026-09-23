import { fireEvent, render, screen, within } from "@solidjs/testing-library";
import { describe, expect, it } from "vitest";
import { App } from "../../src/app/App";
import {
  milliseconds,
  nickname,
  nomenclatureName,
  pesticideId,
  plantId,
} from "../../src/domain/Journal";
import type { OperationWindow, Plant, PlantId } from "../../src/domain/Journal";
import * as JournalFixtures from "./JournalTestSupport";

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
    const journal = JournalFixtures.buildJournal({
      getAttentionResults: [
        JournalFixtures.attentionResult([
          JournalFixtures.unavailableAttention(JournalFixtures.monstera()),
          JournalFixtures.scoredAttention(JournalFixtures.ficus(), "redAlert"),
        ]),
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
      "2026-02-02EditCareMoistureModerate +ActionsNone recorded",
      "2026-03-03EditRepotSubstratePerlite 100%",
      "2026-04-05EditCareMoistureWetActionsWatered, PesticidePesticidesNeem oilNoteRecovered",
    ]);
    expect(within(card).getByText("2026-04-05")).toHaveAttribute(
      "datetime",
      "2026-04-04T22:30:00Z",
    );
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
        name: "Edit historical care operation 1 from 2026-04-05",
      }),
    );
    expect(screen.getByRole("dialog", { name: "Operation editor" })).toBeInTheDocument();
  });

  it("should render backend attention in presentation order", async () => {
    const plant = (id: string, plantNickname: string): Plant => ({
      ...JournalFixtures.ficus(),
      id: plantId(id),
      details: {
        ...JournalFixtures.ficus().details,
        maybeNickname: nickname(plantNickname),
      },
    });
    const unknown = plant("unknown", "Unknown");
    const urgent = plant("urgent", "Urgent");
    const recentlyWatered = plant("current", "Current");
    const operationWindows: { plantId: PlantId; window: OperationWindow }[] = [];
    const journal = JournalFixtures.buildJournal({
      getAttentionResults: [
        JournalFixtures.attentionResult([
          JournalFixtures.scoredAttention(
            recentlyWatered,
            "current",
            milliseconds("10"),
            milliseconds("1"),
          ),
          JournalFixtures.scoredAttention(urgent, "redAlert", milliseconds("0"), milliseconds("1")),
          JournalFixtures.unavailableAttention(unknown),
        ]),
      ],
      getOperationsByPlantId: Object.fromEntries(
        [unknown, urgent, recentlyWatered].map(({ id }) => [
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
      getAttentionResults: [
        JournalFixtures.attentionResult([
          JournalFixtures.unavailableAttention(JournalFixtures.ficus()),
        ]),
      ],
      getOperationsByPlantId: { p1: [JournalFixtures.operationsPage()] },
    });

    render(() => <App journal={journal} />);

    expect(await screen.findByText("active plant")).toBeInTheDocument();
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

  it("should report an operation history read failure without showing its reason", async () => {
    const reason = new Error("private details");
    const journal = JournalFixtures.buildJournal({
      getAttentionResults: [
        JournalFixtures.attentionResult([
          JournalFixtures.unavailableAttention(JournalFixtures.ficus()),
        ]),
      ],
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
