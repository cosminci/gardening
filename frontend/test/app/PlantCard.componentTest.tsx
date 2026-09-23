import { fireEvent, render, screen } from "@solidjs/testing-library";
import { describe, expect, it, vi } from "vitest";
import { pesticideLabel, substrateComponentLabel } from "../../src/app/JournalLabels";
import { PlantCard } from "../../src/app/PlantCard";
import { nomenclatureName, pesticideId, substrateComponentId } from "../../src/domain/Journal";
import { care, ficus, scoredAttention, unavailableAttention } from "./JournalTestSupport";

describe("plant operation controls", () => {
  it("should distinguish edit controls for same-day care operations", () => {
    const componentId = substrateComponentId("00000000-0000-4000-8000-000000000003");
    const onEdit = vi.fn();
    render(() => (
      <PlantCard
        attention={scoredAttention(ficus())}
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
      name: "Edit recent care operation 1 from 2026-03-03",
    });
    expect(firstEdit).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Edit recent care operation 2 from 2026-03-03" }),
    ).toBeInTheDocument();
    fireEvent.click(firstEdit);
    expect(onEdit).toHaveBeenCalledOnce();
  });

  it("should show history access only when older operations exist", () => {
    const props = {
      attention: scoredAttention(ficus()),
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

  it.each([
    ["unknown", unavailableAttention(ficus(), 4), "Watering cadence unknown"],
    ["overdue", scoredAttention(ficus(), "overdue"), "Watering overdue"],
    ["current", scoredAttention(ficus(), "current"), "Watering current"],
  ])("should render %s watering status without a live region", (_, attention, label) => {
    render(() => <PlantCard {...emptyCardProps} attention={attention} />);

    expect(screen.getByText(label)).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("should render an accessible red-alert warning without a live region", () => {
    render(() => (
      <PlantCard {...emptyCardProps} attention={scoredAttention(ficus(), "redAlert")} />
    ));

    expect(screen.getByText("Watering red alert")).toBeInTheDocument();
    expect(screen.getByText("!", { selector: "span" })).toHaveAttribute("aria-hidden", "true");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("should identify an unrecognized persisted substrate component", () => {
    const id = substrateComponentId("10000000-0000-4000-8000-000000000099");
    expect(substrateComponentLabel(id, [])).toBe(id);
  });

  const emptyCardProps = {
    operationPage: { operations: [], hasNextPage: false },
    substrateComponents: [],
    pesticides: [],
    getOperations: () =>
      Promise.resolve({ kind: "read", page: { operations: [], hasNextPage: false } } as const),
    onLog: () => undefined,
    onEdit: () => undefined,
    operationChange: undefined,
  };

  it("should identify an unrecognized persisted pesticide", () => {
    const id = pesticideId("10000000-0000-4000-8001-000000000099");
    expect(pesticideLabel(id, [])).toBe(id);
  });
});
