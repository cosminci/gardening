import { render, screen, within } from "@solidjs/testing-library";
import { describe, expect, it } from "vitest";
import { App } from "../../src/app/App";
import { nomenclatureName, pesticideId } from "../../src/domain/Journal";
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
    const operations = [
      treated,
      JournalFixtures.care({
        id: "o2",
        date: "2026-02-02T00:00:00Z",
        moisture: "moderatePlus",
        actions: new Set(),
      }),
      JournalFixtures.repot("o3", "2026-03-03T00:00:00Z"),
      JournalFixtures.care({ id: "o1", date: "2026-01-01T00:00:00Z", moisture: "dry" }),
    ];
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
    const journal = JournalFixtures.buildJournal({
      getPlantsResult: {
        kind: "read",
        plants: [JournalFixtures.monstera(), JournalFixtures.ficus()],
      },
      getOperationsByPlantId: {
        p1: [{ kind: "read", operations }],
        p2: [{ kind: "read", operations: [] }],
      },
      getPesticidesResult: { kind: "read", entries: pesticideCatalog },
    });

    render(() => <App journal={journal} />);

    const card = await screen.findByRole("article", { name: "Fern" });
    expect(within(card).getByText("Ficus lyrata")).toBeInTheDocument();
    expect(within(card).getByText("Balcony")).toBeInTheDocument();
    expect(within(card).getAllByText("Perlite 100%")).toHaveLength(2);
    expect(within(card).getByRole("list", { name: "Recent operations" })).toBeInTheDocument();
    const renderedOperations = within(card).getAllByRole("listitem");
    expect(renderedOperations[0]).toHaveTextContent(
      "2026-02-02EditCareMoistureModerate +ActionsNone recorded",
    );
    expect(renderedOperations[1]).toHaveTextContent("2026-03-03EditRepotSubstratePerlite 100%");
    expect(renderedOperations[2]).toHaveTextContent(
      "2026-04-05EditCareMoistureWetActionsWatered, PesticidePesticidesNeem oilNoteRecovered",
    );
    expect(within(renderedOperations[2]!).getByText("2026-04-05")).toHaveAttribute(
      "datetime",
      "2026-04-04T22:30:00Z",
    );
    expect(
      screen.getAllByRole("article").map((article) => article.getAttribute("aria-label")),
    ).toEqual(["Fern", "Monstera deliciosa"]);
    expect(
      within(screen.getByRole("article", { name: "Monstera deliciosa" })).getByText(
        "No operations yet.",
      ),
    ).toBeInTheDocument();
  });

  it("should identify a journal containing one active plant", async () => {
    const journal = JournalFixtures.buildJournal({
      getPlantsResult: { kind: "read", plants: [JournalFixtures.ficus()] },
      getOperationsByPlantId: { p1: [{ kind: "read", operations: [] }] },
    });

    render(() => <App journal={journal} />);

    expect(await screen.findByText("active plant")).toBeInTheDocument();
  });

  it("should report a plant read failure without showing its reason", async () => {
    const reason = new Error("private details");
    const journal = JournalFixtures.buildJournal({
      getPlantsResult: { kind: "readFailed", reason },
    });

    render(() => <App journal={journal} />);

    expect(await screen.findByRole("alert")).toHaveTextContent("The journal could not be loaded.");
    expect(screen.queryByText("private details")).not.toBeInTheDocument();
  });

  it("should report an operation history read failure without showing its reason", async () => {
    const reason = new Error("private details");
    const journal = JournalFixtures.buildJournal({
      getPlantsResult: { kind: "read", plants: [JournalFixtures.ficus()] },
      getOperationsByPlantId: { p1: [{ kind: "readFailed", reason }] },
    });

    render(() => <App journal={journal} />);

    expect(await screen.findByRole("alert")).toHaveTextContent("The journal could not be loaded.");
    expect(screen.queryByText("private details")).not.toBeInTheDocument();
  });

  it("should report an unexpected rejected request", async () => {
    const journal = {
      ...JournalFixtures.buildJournal(),
      getPlants: () => Promise.reject(new Error("private details")),
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
