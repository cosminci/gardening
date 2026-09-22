import { fireEvent, render, screen, within } from "@solidjs/testing-library";
import { describe, expect, it } from "vitest";
import { App } from "../../src/app/App";
import {
  location,
  nickname,
  nomenclatureName,
  pesticideId,
  plantId,
  species,
} from "../../src/domain/Journal";
import type { OperationWindow, Plant, PlantId, Urgency } from "../../src/domain/Journal";
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
    const operationWindows: {
      plantId: PlantId;
      window: OperationWindow;
    }[] = [];
    const journal = JournalFixtures.buildJournal({
      getAttentionResults: [
        JournalFixtures.attentionResult([
          JournalFixtures.unavailableAttention(JournalFixtures.monstera()),
          JournalFixtures.inferredAttention(JournalFixtures.ficus(), "redAlert"),
        ]),
      ],
      getOperationsByPlantId: {
        p1: [
          JournalFixtures.operationsPage([operations[0]!, operations[2]!, operations[1]!], true),
        ],
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

  it("should order unknown cadence first, then urgency, then plant fields", async () => {
    const plant = (
      id: string,
      plantLocation: string,
      plantSpecies: string,
      plantNickname: string | null,
    ): Plant => ({
      ...JournalFixtures.ficus(),
      id: plantId(id),
      details: {
        ...JournalFixtures.ficus().details,
        location: location(plantLocation),
        species: species(plantSpecies),
        maybeNickname: plantNickname === null ? null : nickname(plantNickname),
      },
    });
    const finite = (numeratorNanos: string, denominatorNanos: string): Urgency => ({
      kind: "finite",
      numeratorNanos,
      denominatorNanos,
    });
    const unknownFirst = plant("z", "Balcony", "Ficus", null);
    const unknownLast = plant("y", "Kitchen", "Anthurium", null);
    const urgent = plant("c", "Office", "Ficus", null);
    const tiedLocationFirst = plant("f", "Balcony", "Zamioculcas", null);
    const tiedSpeciesFirst = plant("b", "Office", "Ficus", null);
    const tiedNoName = plant("a", "Office", "Monstera", null);
    const tiedWithName = plant("d", "Office", "Monstera", "Monty");
    const sameNamedLast = plant("e", "Office", "Monstera", "Monty");
    const tiedOtherName = plant("g", "Office", "Monstera", "Zed");
    const recentlyWatered = plant("h", "Office", "Orchid", null);
    const operationWindows: { plantId: PlantId; window: OperationWindow }[] = [];
    const journal = JournalFixtures.buildJournal({
      getAttentionResults: [
        JournalFixtures.attentionResult([
          JournalFixtures.inferredAttention(recentlyWatered, "current", finite("1", "10")),
          JournalFixtures.inferredAttention(tiedOtherName),
          JournalFixtures.inferredAttention(sameNamedLast),
          JournalFixtures.inferredAttention(tiedWithName),
          JournalFixtures.inferredAttention(tiedNoName),
          JournalFixtures.inferredAttention(tiedSpeciesFirst),
          JournalFixtures.inferredAttention(tiedLocationFirst),
          JournalFixtures.inferredAttention(urgent, "redAlert", { kind: "unbounded" }),
          JournalFixtures.unavailableAttention(unknownLast),
          JournalFixtures.unavailableAttention(unknownFirst),
        ]),
      ],
      getOperationsByPlantId: Object.fromEntries(
        [
          unknownFirst,
          unknownLast,
          urgent,
          tiedLocationFirst,
          tiedSpeciesFirst,
          tiedNoName,
          tiedWithName,
          sameNamedLast,
          tiedOtherName,
          recentlyWatered,
        ].map(({ id }) => [id, [JournalFixtures.operationsPage()]]),
      ),
      operationWindows,
    });

    render(() => <App journal={journal} />);

    await screen.findAllByRole("article");
    expect(operationWindows.map(({ plantId: id }) => id)).toEqual([
      "z",
      "y",
      "c",
      "f",
      "b",
      "a",
      "d",
      "e",
      "g",
      "h",
    ]);
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
