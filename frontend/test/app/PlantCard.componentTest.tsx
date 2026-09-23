import { fireEvent, render, screen } from "@solidjs/testing-library";
import { describe, expect, it, vi } from "vitest";
import { pesticideLabel, substrateComponentLabel } from "../../src/app/JournalLabels";
import { PlantCard } from "../../src/app/PlantCard";
import { nomenclatureName, pesticideId, substrateComponentId } from "../../src/domain/Journal";
import { care, ficus, inferredAttention, unavailableAttention } from "./JournalTestSupport";

describe("plant operation controls", () => {
  it("should distinguish edit controls for same-day care operations", () => {
    const componentId = substrateComponentId("00000000-0000-4000-8000-000000000003");
    const onEdit = vi.fn();
    render(() => (
      <PlantCard
        attention={inferredAttention(ficus())}
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
      attention: inferredAttention(ficus()),
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

  it("should distinguish watering states without creating per-card live regions", () => {
    const props = {
      operationPage: { operations: [], hasNextPage: false },
      substrateComponents: [],
      pesticides: [],
      getOperations: () =>
        Promise.resolve({ kind: "read", page: { operations: [], hasNextPage: false } } as const),
      onLog: () => undefined,
      onEdit: () => undefined,
      operationChange: undefined,
    };
    const { unmount: unmountUnknown } = render(() => (
      <PlantCard {...props} attention={unavailableAttention(ficus(), 4)} />
    ));
    const unknown = screen.getByText("Watering cadence unknown").closest(".watering-status");
    expect(unknown).toBeInTheDocument();
    expect(unknown).not.toHaveAttribute("role");
    unmountUnknown();

    const { unmount: unmountOverdue } = render(() => (
      <PlantCard {...props} attention={inferredAttention(ficus(), "overdue")} />
    ));
    const overdue = screen.getByText("Watering overdue").closest(".watering-status");
    expect(overdue).toBeInTheDocument();
    expect(overdue).not.toHaveAttribute("role");
    unmountOverdue();

    const { unmount: unmountCurrent } = render(() => (
      <PlantCard {...props} attention={inferredAttention(ficus(), "current")} />
    ));
    const current = screen.getByText("Watering current").closest(".watering-status");
    expect(current).toBeInTheDocument();
    expect(current).not.toHaveAttribute("role");
    unmountCurrent();

    render(() => <PlantCard {...props} attention={inferredAttention(ficus(), "redAlert")} />);
    const warning = screen.getByText("Watering red alert").closest(".watering-status");
    expect(warning).toBeInTheDocument();
    expect(warning).not.toHaveAttribute("role");
    expect(warning).toHaveTextContent("!");
    expect(warning).toHaveTextContent("Watering red alert");
    expect(screen.getByText("!", { selector: "span" })).toHaveAttribute("aria-hidden", "true");
  });

  it("should identify an unrecognized persisted substrate component", () => {
    const id = substrateComponentId("10000000-0000-4000-8000-000000000099");
    expect(substrateComponentLabel(id, [])).toBe(id);
  });

  it("should identify an unrecognized persisted pesticide", () => {
    const id = pesticideId("10000000-0000-4000-8001-000000000099");
    expect(pesticideLabel(id, [])).toBe(id);
  });
});
