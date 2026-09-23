import { fireEvent, render, screen, within } from "@solidjs/testing-library";
import { afterEach, describe, expect, it, vi } from "vitest";
import { PlantCard } from "../../src/app/PlantCard";
import {
  instant,
  milliseconds,
  nomenclatureName,
  pesticideId,
  substrateComponentId,
} from "../../src/domain/Journal";
import { care, ficus } from "./JournalTestSupport";

const ficusPlant = ficus();
const attentionMeasuredAt = instant("2026-01-01T00:00:00Z");
const currentFicusAttention = {
  plant: ficusPlant,
  watering: {
    kind: "current" as const,
    sampleCount: 5,
    averageInterval: milliseconds("187200000"),
    elapsed: milliseconds("144000000"),
  },
};
const unknownFicusAttention = {
  plant: ficusPlant,
  watering: {
    kind: "unavailable" as const,
    sampleCount: 4,
    maybeElapsed: null,
  },
};
const redAlertFicusAttention = {
  plant: ficusPlant,
  watering: {
    kind: "redAlert" as const,
    sampleCount: 5,
    averageInterval: milliseconds("3600000"),
    elapsed: milliseconds("176400000"),
  },
};
const overdueFicusAttention = {
  plant: ficusPlant,
  watering: {
    kind: "overdue" as const,
    sampleCount: 5,
    averageInterval: milliseconds("86400000"),
    elapsed: milliseconds("90000000"),
  },
};
const emptyCardProps = {
  measuredAt: attentionMeasuredAt,
  operationPage: { operations: [], hasNextPage: false },
  substrateComponents: [],
  pesticides: [],
  getOperations: () =>
    Promise.resolve({ kind: "read", page: { operations: [], hasNextPage: false } } as const),
  onLog: () => undefined,
  onEdit: () => undefined,
  operationChange: undefined,
};

describe("plant operation controls", () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it("should distinguish edit controls for same-day care operations", () => {
    const componentId = substrateComponentId("00000000-0000-4000-8000-000000000003");
    const onEdit = vi.fn();
    render(() => (
      <PlantCard
        attention={currentFicusAttention}
        measuredAt={attentionMeasuredAt}
        operationPage={{
          operations: [
            care({ id: "o2", date: "2026-03-03T12:00:00Z", moisture: "wet" }),
            care({ id: "o1", date: "2026-03-03T08:00:00Z", moisture: "dry" }),
          ],
          hasNextPage: false,
        }}
        substrateComponents={[
          {
            id: componentId,
            data: { name: nomenclatureName("Perlite"), maybeInfo: null },
          },
        ]}
        pesticides={[]}
        getOperations={() =>
          Promise.resolve({ kind: "read", page: { operations: [], hasNextPage: false } })
        }
        operationChange={undefined}
        onLog={() => undefined}
        onEdit={onEdit}
      />
    ));

    const firstEdit = screen.getByRole("button", {
      name: "Edit recent care operation 1 from 3rd of March",
    });
    expect(firstEdit).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Edit recent care operation 2 from 3rd of March" }),
    ).toBeInTheDocument();
    fireEvent.click(firstEdit);
    expect(onEdit).toHaveBeenCalledOnce();
  });

  it("should use correct English ordinal dates for recent operations including teen days", () => {
    const days = [1, 2, 3, 11, 12, 13, 21, 22, 23, 31];
    render(() => (
      <PlantCard
        {...emptyCardProps}
        attention={currentFicusAttention}
        operationPage={{
          operations: days.map((day) =>
            care({
              id: `o${String(day)}`,
              date: `2026-03-${String(day).padStart(2, "0")}T08:00:00Z`,
              moisture: "wet",
            }),
          ),
          hasNextPage: false,
        }}
      />
    ));

    const expectedDates = [
      "1st of March",
      "2nd of March",
      "3rd of March",
      "11th of March",
      "12th of March",
      "13th of March",
      "21st of March",
      "22nd of March",
      "23rd of March",
      "31st of March",
    ];
    expect(screen.getAllByRole("time").map((time) => time.textContent)).toEqual(
      expectedDates.toReversed(),
    );
    expect(screen.getAllByRole("time")[1]).toHaveAttribute("datetime", "2026-03-23T08:00:00Z");
  });

  it("should show history access only when older operations exist", () => {
    const props = {
      attention: currentFicusAttention,
      measuredAt: attentionMeasuredAt,
      substrateComponents: [],
      pesticides: [],
      getOperations: () =>
        Promise.resolve({ kind: "read", page: { operations: [], hasNextPage: false } } as const),
      onLog: () => undefined,
      onEdit: () => undefined,
      operationChange: undefined,
    };
    const { unmount } = render(() => (
      <PlantCard
        {...props}
        operationPage={{
          operations: [care({ id: "o1", date: "2026-03-03T08:00:00Z", moisture: "dry" })],
          hasNextPage: false,
        }}
      />
    ));
    expect(
      screen.queryByRole("button", { name: "Show operation history" }),
    ).not.toBeInTheDocument();
    unmount();

    render(() => (
      <PlantCard
        {...props}
        operationPage={{
          operations: [care({ id: "o1", date: "2026-03-03T08:00:00Z", moisture: "dry" })],
          hasNextPage: true,
        }}
      />
    ));
    expect(screen.getByRole("button", { name: "Show operation history" })).toBeInTheDocument();
  });

  it(`should render ${unknownFicusAttention.watering.kind} watering with an unavailable cadence`, () => {
    render(() => <PlantCard {...emptyCardProps} attention={unknownFicusAttention} />);

    expect(screen.getByLabelText("Watering cadence unavailable")).toHaveTextContent("?");
    fireEvent.focus(screen.getByRole("button", { name: "Watering attention details for Fern" }));
    const expectedDetails = "Insufficient watering operations.";
    expect(screen.getByRole("tooltip")).toHaveTextContent(expectedDetails);
  });

  it(`should render ${currentFicusAttention.watering.kind} watering with time until it is due`, () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-01-01T00:03:00Z"));
    render(() => <PlantCard {...emptyCardProps} attention={currentFicusAttention} />);

    expect(screen.getByLabelText("Watering current")).toHaveTextContent("✓");
    expect(screen.getByText("in 12h")).toBeInTheDocument();
    fireEvent.focus(screen.getByRole("button", { name: "Watering attention details for Fern" }));
    const tooltip = screen.getByRole("tooltip");
    expect(tooltip.querySelector("dl")).toHaveClass("watering-attention__details");
    expect(within(tooltip).getByText("Watering operations")).toBeInTheDocument();
    expect(within(tooltip).getByText("5 considered")).toBeInTheDocument();
    expect(within(tooltip).getByText("2 days and 4 hours")).toBeInTheDocument();
    expect(within(tooltip).getByText("3 minutes ago")).toBeInTheDocument();
  });

  it(`should render ${overdueFicusAttention.watering.kind} watering with the overdue duration`, () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-01-01T00:03:00Z"));
    render(() => <PlantCard {...emptyCardProps} attention={overdueFicusAttention} />);

    expect(screen.getByLabelText("Watering overdue")).toHaveTextContent("!");
    expect(screen.getByText("late 1h")).toBeInTheDocument();
    fireEvent.focus(screen.getByRole("button", { name: "Watering attention details for Fern" }));
    expect(within(screen.getByRole("tooltip")).getByText("1 day")).toBeInTheDocument();
  });

  it(`should render ${redAlertFicusAttention.watering.kind} watering with the overdue duration`, () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-01-01T00:01:00Z"));
    render(() => <PlantCard {...emptyCardProps} attention={redAlertFicusAttention} />);

    expect(screen.getByLabelText("Watering red alert")).toHaveTextContent("×");
    expect(screen.getByText("late 2d")).toBeInTheDocument();
    fireEvent.focus(screen.getByRole("button", { name: "Watering attention details for Fern" }));
    expect(within(screen.getByRole("tooltip")).getByText("1 hour")).toBeInTheDocument();
    expect(within(screen.getByRole("tooltip")).getByText("1 minute ago")).toBeInTheDocument();
  });

  it("should render an unrecognized persisted substrate component", () => {
    const expectedComponentId = substrateComponentId("00000000-0000-4000-8000-000000000003");
    render(() => <PlantCard {...emptyCardProps} attention={currentFicusAttention} />);

    expect(screen.getByText(`${expectedComponentId} 100%`)).toBeInTheDocument();
  });

  it("should render an unrecognized persisted pesticide", () => {
    const expectedPesticideId = pesticideId("10000000-0000-4000-8001-000000000099");
    render(() => (
      <PlantCard
        {...emptyCardProps}
        attention={currentFicusAttention}
        operationPage={{
          operations: [
            care({
              id: "pesticide",
              date: "2026-03-03T08:00:00Z",
              moisture: "dry",
              actions: new Set(["pesticide"]),
              pesticides: new Set([expectedPesticideId]),
            }),
          ],
          hasNextPage: false,
        }}
      />
    ));

    expect(screen.getByText(expectedPesticideId)).toBeInTheDocument();
  });
});
