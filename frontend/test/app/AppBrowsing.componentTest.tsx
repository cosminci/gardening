import { render, screen, within } from "@solidjs/testing-library";
import { describe, expect, it } from "vitest";
import { App } from "../../src/app/App";
import { buildJournal, care, ficus, monstera, repot } from "./JournalTestSupport";

describe("browsing the journal", () => {
  it("should show each plant with its three most recent operations, oldest first", async () => {
    const operations = [
      care("o4", "2026-04-04T00:00:00Z", "wet", "Recovered"),
      care("o2", "2026-02-02T00:00:00Z", "moderatePlus"),
      repot("o3", "2026-03-03T00:00:00Z"),
      care("o1", "2026-01-01T00:00:00Z", "dry"),
    ];
    const journal = buildJournal({
      getPlantsResult: { kind: "read", plants: [ficus(), monstera()] },
      getOperationsResults: [
        { kind: "read", operations },
        { kind: "read", operations: [] },
      ],
    });

    render(() => <App journal={journal} />);

    const card = await screen.findByRole("article", { name: "Fern" });
    expect(within(card).getByText("Ficus lyrata")).toBeInTheDocument();
    expect(within(card).getByText("Balcony")).toBeInTheDocument();
    expect(within(card).getByText("Perlite 100%")).toBeInTheDocument();
    expect(
      within(card)
        .getAllByRole("listitem")
        .map((item) => item.querySelector("span")?.textContent),
    ).toEqual([
      "2026-02-02 — Moderate + — Watered",
      "2026-03-03 — Repotted — Perlite 100%",
      "2026-04-04 — Wet — Watered — Recovered",
    ]);
    expect(
      within(screen.getByRole("article", { name: "Monstera deliciosa" })).getByText(
        "No operations yet.",
      ),
    ).toBeInTheDocument();
  });

  it("should identify a journal containing one active plant", async () => {
    const journal = buildJournal({
      getPlantsResult: { kind: "read", plants: [ficus()] },
      getOperationsResults: [{ kind: "read", operations: [] }],
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
      getOperationsResults: [{ kind: "readFailed", reason }],
    });

    render(() => <App journal={journal} />);

    expect(await screen.findByRole("alert")).toHaveTextContent("The journal could not be loaded.");
    expect(screen.queryByText("private details")).not.toBeInTheDocument();
  });
});
