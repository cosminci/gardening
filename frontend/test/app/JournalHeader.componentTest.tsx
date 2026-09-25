import { render, screen } from "@solidjs/testing-library";
import { afterEach, describe, expect, it, vi } from "vitest";
import { JournalHeader } from "../../src/app/JournalHeader";
import type { FeedConnectionState } from "../../src/domain/PlantAttention";

const baseProps = {
  loaded: true,
  gardenCount: 2,
  cemeteryCount: 1,
  selected: "garden" as const,
  connectionState: "connected" as FeedConnectionState,
  lastUpdateAt: undefined as number | undefined,
  onSelect: () => undefined,
  onAddPlant: () => undefined,
};

describe("journal header backend indicator", () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it("should label the backend indicator with its connection state", () => {
    const states: FeedConnectionState[] = ["connecting", "connected", "disconnected"];

    for (const state of states) {
      const { unmount } = render(() => <JournalHeader {...baseProps} connectionState={state} />);

      const indicator = screen.getByRole("status", { name: `Backend ${state}` });
      expect(indicator).toHaveTextContent("Backend");
      unmount();
    }
  });

  it("should place the backend indicator before the add-plant control", () => {
    render(() => <JournalHeader {...baseProps} />);

    const indicator = screen.getByRole("status", { name: "Backend connected" });
    const addPlant = screen.getByRole("button", { name: "Add plant" });

    const indicatorPrecedesAddPlant =
      indicator.compareDocumentPosition(addPlant) & Node.DOCUMENT_POSITION_FOLLOWING;
    expect(indicatorPrecedesAddPlant).toBeTruthy();
  });

  it("should keep the backend indicator visible before the journal loads", () => {
    render(() => <JournalHeader {...baseProps} loaded={false} />);

    expect(screen.getByRole("status", { name: "Backend connected" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Add plant" })).toBeNull();
  });

  it("should await the first attention update before reporting freshness", () => {
    render(() => <JournalHeader {...baseProps} lastUpdateAt={undefined} />);

    expect(screen.getByText("Awaiting first update")).toBeInTheDocument();
  });

  it("should report and tick the elapsed time since the last attention update", () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-01-01T00:00:05Z"));
    const lastUpdateAt = Date.parse("2026-01-01T00:00:00Z");

    render(() => <JournalHeader {...baseProps} lastUpdateAt={lastUpdateAt} />);
    expect(screen.getByText("Updated 5s ago")).toBeInTheDocument();

    vi.advanceTimersByTime(3000);

    expect(screen.getByText("Updated 8s ago")).toBeInTheDocument();
  });
});
