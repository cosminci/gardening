import { fireEvent, render, screen, within } from "@solidjs/testing-library";
import { afterEach, describe, expect, it, vi } from "vitest";
import { PlantCard } from "../../src/app/PlantCard";
import {
  instant,
  milliseconds,
  substrateComponentName,
  pesticideId,
  substrateComponentId,
} from "../../src/domain/Journal";
import { care, ficus } from "./JournalTestSupport";

const ficusPlant = ficus();
const attentionMeasuredAt = instant("2026-01-01T00:00:00Z");
const currentWatering = {
  kind: "current" as const,
  sampleCount: 5,
  averageInterval: milliseconds("187200000"),
  elapsed: milliseconds("144000000"),
};
const unknownWatering = {
  kind: "unavailable" as const,
  sampleCount: 4,
  maybeElapsed: null,
};
const redAlertWatering = {
  kind: "redAlert" as const,
  sampleCount: 5,
  averageInterval: milliseconds("3600000"),
  elapsed: milliseconds("176400000"),
};
const overdueWatering = {
  kind: "overdue" as const,
  sampleCount: 5,
  averageInterval: milliseconds("86400000"),
  elapsed: milliseconds("90000000"),
};
const emptyCardProps = {
  plant: ficusPlant,
  measuredAt: attentionMeasuredAt,
  operationPage: { operations: [], hasNextPage: false },
  substrateComponents: [],
  pesticides: [],
  getOperations: () =>
    Promise.resolve({ kind: "read", page: { operations: [], hasNextPage: false } } as const),
  onLog: () => undefined,
  onArchive: () => undefined,
  onEditPlant: () => undefined,
  onEdit: () => undefined,
  operationChange: undefined,
};
const archivedFicus = {
  ...ficusPlant,
  details: { ...ficusPlant.details, status: "archived" as const },
};
const archivedCardProps = {
  kind: "cemetery" as const,
  plant: archivedFicus,
  dates: { kind: "empty" as const },
  operationPage: emptyCardProps.operationPage,
  substrateComponents: emptyCardProps.substrateComponents,
  pesticides: emptyCardProps.pesticides,
  getOperations: emptyCardProps.getOperations,
  onEdit: emptyCardProps.onEdit,
  operationChange: emptyCardProps.operationChange,
};

describe("plant cards", () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it("should show a pending attention indicator when no projection has arrived", () => {
    render(() => <PlantCard {...emptyCardProps} />);

    const pending = screen.getByRole("complementary", { name: "Attention pending for Fern" });
    expect(pending).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Watering attention details for Fern" }),
    ).toBeNull();
  });

  it("should distinguish edit controls for same-day care operations", () => {
    const componentId = substrateComponentId("00000000-0000-4000-8000-000000000003");
    const onEdit = vi.fn();
    render(() => (
      <PlantCard
        plant={ficusPlant}
        watering={currentWatering}
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
            data: { name: substrateComponentName("Perlite"), maybeInfo: null },
            status: "active",
          },
        ]}
        pesticides={[]}
        getOperations={() =>
          Promise.resolve({ kind: "read", page: { operations: [], hasNextPage: false } })
        }
        operationChange={undefined}
        onLog={() => undefined}
        onArchive={() => undefined}
        onEditPlant={() => undefined}
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

  it("should display ordinal dates for the three most recent operations", () => {
    const days = [11, 13, 23];
    render(() => (
      <PlantCard
        {...emptyCardProps}
        watering={currentWatering}
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

    const expectedDates = ["11th of March", "13th of March", "23rd of March"];
    const actualDates = screen.getAllByRole("time").map((time) => time.textContent);
    expect(actualDates).toEqual(expectedDates.toReversed());
    expect(screen.getAllByRole("time")[0]).toHaveAttribute("datetime", "2026-03-23T08:00:00Z");
  });

  it("should show history access only when older operations exist", () => {
    const props = {
      plant: ficusPlant,
      watering: currentWatering,
      measuredAt: attentionMeasuredAt,
      substrateComponents: [],
      pesticides: [],
      getOperations: () =>
        Promise.resolve({ kind: "read", page: { operations: [], hasNextPage: false } } as const),
      onLog: () => undefined,
      onArchive: () => undefined,
      onEditPlant: () => undefined,
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

  it("should show recorded cemetery dates without an add-operation control", () => {
    const dates = {
      kind: "recorded" as const,
      first: instant("2026-02-01T10:00:00Z"),
      last: instant("2026-04-03T18:00:00Z"),
    };

    render(() => <PlantCard {...archivedCardProps} dates={dates} />);

    expect(screen.getByText("RIP")).toBeInTheDocument();
    expect(screen.getByText("01.02.2026")).toBeInTheDocument();
    expect(screen.getByText("03.04.2026")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Log operation for Fern" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Archive Fern" })).toBeNull();
  });

  it("should show unknown dates for a cemetery plant without operations", () => {
    render(() => <PlantCard {...archivedCardProps} />);

    expect(screen.getByText("Dates unknown")).toBeInTheDocument();
  });

  it("should show the same recorded start and end date for a single operation", () => {
    const recordedDate = instant("2026-02-01T10:00:00Z");
    const dates = { kind: "recorded" as const, first: recordedDate, last: recordedDate };

    render(() => <PlantCard {...archivedCardProps} dates={dates} />);

    expect(screen.getAllByText("01.02.2026")).toHaveLength(2);
  });

  it("should offer archiving from an active plant summary", () => {
    const onArchive = vi.fn();

    render(() => (
      <PlantCard {...emptyCardProps} watering={currentWatering} onArchive={onArchive} />
    ));

    fireEvent.click(screen.getByRole("button", { name: "Archive Fern" }));

    expect(onArchive).toHaveBeenCalledOnce();
  });

  it("should offer editing from an active plant summary", () => {
    const onEditPlant = vi.fn();

    render(() => (
      <PlantCard {...emptyCardProps} watering={currentWatering} onEditPlant={onEditPlant} />
    ));

    fireEvent.click(screen.getByRole("button", { name: "Edit Fern" }));

    expect(onEditPlant).toHaveBeenCalledOnce();
    expect(onEditPlant).toHaveBeenCalledWith(ficusPlant);
  });

  it("should not offer editing or archiving a cemetery plant", () => {
    render(() => <PlantCard {...archivedCardProps} />);

    expect(screen.queryByRole("button", { name: "Edit Fern" })).not.toBeInTheDocument();
  });

  it(`should render ${unknownWatering.kind} watering with an unavailable cadence`, () => {
    render(() => <PlantCard {...emptyCardProps} watering={unknownWatering} />);

    expect(screen.getByLabelText("Watering cadence unavailable")).toHaveTextContent("?");
    fireEvent.focus(screen.getByRole("button", { name: "Watering attention details for Fern" }));
    const expectedDetails = "Insufficient watering operations.";
    expect(screen.getByRole("tooltip")).toHaveTextContent(expectedDetails);
  });

  it(`should render ${currentWatering.kind} watering with time until it is due`, () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-01-01T00:03:00Z"));
    render(() => <PlantCard {...emptyCardProps} watering={currentWatering} />);

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

  it(`should render ${overdueWatering.kind} watering with the overdue duration`, () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-01-01T00:03:00Z"));
    render(() => <PlantCard {...emptyCardProps} watering={overdueWatering} />);

    expect(screen.getByLabelText("Watering overdue")).toHaveTextContent("!");
    expect(screen.getByText("late 1h")).toBeInTheDocument();
    fireEvent.focus(screen.getByRole("button", { name: "Watering attention details for Fern" }));
    expect(within(screen.getByRole("tooltip")).getByText("1 day")).toBeInTheDocument();
  });

  it(`should render ${redAlertWatering.kind} watering with the overdue duration`, () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-01-01T00:01:00Z"));
    render(() => <PlantCard {...emptyCardProps} watering={redAlertWatering} />);

    expect(screen.getByLabelText("Watering red alert")).toHaveTextContent("×");
    expect(screen.getByText("late 2d")).toBeInTheDocument();
    fireEvent.focus(screen.getByRole("button", { name: "Watering attention details for Fern" }));
    expect(within(screen.getByRole("tooltip")).getByText("1 hour")).toBeInTheDocument();
    expect(within(screen.getByRole("tooltip")).getByText("1 minute ago")).toBeInTheDocument();
  });

  it("should render an unrecognized persisted substrate component", () => {
    const expectedComponentId = substrateComponentId("00000000-0000-4000-8000-000000000003");
    render(() => <PlantCard {...emptyCardProps} watering={currentWatering} />);

    expect(screen.getByText(`${expectedComponentId} 100%`)).toBeInTheDocument();
  });

  it("should render an unrecognized persisted pesticide", () => {
    const expectedPesticideId = pesticideId("10000000-0000-4000-8001-000000000099");
    render(() => (
      <PlantCard
        {...emptyCardProps}
        watering={currentWatering}
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
