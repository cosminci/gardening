import { fireEvent, render, screen } from "@solidjs/testing-library";
import { describe, expect, it, vi } from "vitest";
import { pesticideLabel, substrateComponentLabel } from "../../src/app/JournalLabels";
import { PlantCard } from "../../src/app/PlantCard";
import { nomenclatureName, pesticideId, substrateComponentId } from "../../src/domain/Journal";
import { care, ficus } from "./JournalTestSupport";

describe("plant operation controls", () => {
  it("should distinguish edit controls for same-day care operations", () => {
    const componentId = substrateComponentId("00000000-0000-4000-8000-000000000003");
    const onEdit = vi.fn();
    render(() => (
      <PlantCard
        plant={ficus()}
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
      name: "Edit care operation 1 from 2026-03-03",
    });
    expect(firstEdit).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Edit care operation 2 from 2026-03-03" }),
    ).toBeInTheDocument();
    fireEvent.click(firstEdit);
    expect(onEdit).toHaveBeenCalledOnce();
  });

  it("should show history access only when older operations exist", () => {
    const props = {
      plant: ficus(),
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

  it("should identify an unrecognized persisted substrate component", () => {
    const id = substrateComponentId("10000000-0000-4000-8000-000000000099");
    expect(substrateComponentLabel(id, [])).toBe(id);
  });

  it("should identify an unrecognized persisted pesticide", () => {
    const id = pesticideId("10000000-0000-4000-8001-000000000099");
    expect(pesticideLabel(id, [])).toBe(id);
  });
});
