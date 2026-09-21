import { render, screen, within } from "@solidjs/testing-library";
import { describe, expect, it } from "vitest";
import { App } from "../../src/app/App";
import { buildJournal, care, ficus, monstera, repot } from "./JournalTestSupport";

describe("browsing the journal", () => {
  it("should show each plant with its three most recent operations, oldest first", async () => {
    const operations = [
      care("o4", "2026-04-04T22:30:00Z", "wet", "Recovered"),
      care("o2", "2026-02-02T00:00:00Z", "moderatePlus", null, new Set()),
      repot("o3", "2026-03-03T00:00:00Z"),
      care("o1", "2026-01-01T00:00:00Z", "dry"),
    ];
    const journal = buildJournal({
      getPlantsResult: { kind: "read", plants: [monstera(), ficus()] },
      getOperationsByPlantId: {
        p1: { kind: "read", operations },
        p2: { kind: "read", operations: [] },
      },
    });

    render(() => <App journal={journal} />);

    const card = await screen.findByRole("article", { name: "Fern" });
    expect(within(card).getByText("Ficus lyrata")).toBeInTheDocument();
    expect(within(card).getByText("Balcony")).toBeInTheDocument();
    expect(within(card).getAllByText("Perlite 100%")).toHaveLength(2);
    expect(within(card).getByRole("list", { name: "Recent operations" })).toBeInTheDocument();
    const renderedOperations = within(card).getAllByRole("listitem");
    expect(renderedOperations[0]).toHaveTextContent(
      "2026-02-02CareMoistureModerate +ActionsNone recorded",
    );
    expect(renderedOperations[1]).toHaveTextContent("2026-03-03RepotSubstratePerlite 100%");
    expect(renderedOperations[2]).toHaveTextContent(
      "2026-04-05CareMoistureWetActionsWateredNoteRecovered",
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
    const journal = buildJournal({
      getPlantsResult: { kind: "read", plants: [ficus()] },
      getOperationsByPlantId: { p1: { kind: "read", operations: [] } },
    });

    render(() => <App journal={journal} />);

    expect(await screen.findByText("active plant")).toBeInTheDocument();
  });

  it("should report a plant read failure without showing its reason", async () => {
    const reason = new Error("private details");
    const journal = buildJournal({ getPlantsResult: { kind: "readFailed", reason } });

    render(() => <App journal={journal} />);

    expect(await screen.findByRole("alert")).toHaveTextContent("The journal could not be loaded.");
    expect(screen.queryByText("private details")).not.toBeInTheDocument();
  });

  it("should report an operation history read failure without showing its reason", async () => {
    const reason = new Error("private details");
    const journal = buildJournal({
      getPlantsResult: { kind: "read", plants: [ficus()] },
      getOperationsByPlantId: { p1: { kind: "readFailed", reason } },
    });

    render(() => <App journal={journal} />);

    expect(await screen.findByRole("alert")).toHaveTextContent("The journal could not be loaded.");
    expect(screen.queryByText("private details")).not.toBeInTheDocument();
  });

  it("should report an unexpected rejected request", async () => {
    const journal = {
      ...buildJournal(),
      getPlants: () => Promise.reject(new Error("private details")),
    };

    render(() => <App journal={journal} />);

    expect(await screen.findByRole("alert")).toHaveTextContent("The journal could not be loaded.");
    expect(screen.queryByText("private details")).not.toBeInTheDocument();
  });

  it("should report corrupted plant records", async () => {
    const journal = buildJournal({
      getPlantsResult: {
        kind: "corrupted",
        details: [{ record: { kind: "plant", id: ficus().id }, reason: new Error("corrupt") }],
      },
    });

    render(() => <App journal={journal} />);

    expect(await screen.findByRole("alert")).toHaveTextContent("The journal could not be loaded.");
  });

  it("should report corrupted operation records", async () => {
    const operation = repot("o1", "2026-01-01T00:00:00Z");
    const journal = buildJournal({
      getPlantsResult: { kind: "read", plants: [ficus()] },
      getOperationsByPlantId: {
        p1: {
          kind: "corrupted",
          details: [
            { record: { kind: "operation", id: operation.id }, reason: new Error("corrupt") },
          ],
        },
      },
    });

    render(() => <App journal={journal} />);

    expect(await screen.findByRole("alert")).toHaveTextContent("The journal could not be loaded.");
  });
});
