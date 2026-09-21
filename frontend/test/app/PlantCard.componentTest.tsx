import { render, screen } from "@solidjs/testing-library";
import { describe, expect, it } from "vitest";
import { substrateComponentLabel } from "../../src/app/JournalLabels";
import { PlantCard } from "../../src/app/PlantCard";
import { substrateComponentId } from "../../src/domain/Journal";
import { care, ficus } from "./JournalTestSupport";

describe("plant operation controls", () => {
  it("should distinguish edit controls for same-day care operations", () => {
    render(() => (
      <PlantCard
        plant={ficus()}
        operations={[
          care("o1", "2026-03-03T08:00:00Z", "dry"),
          care("o2", "2026-03-03T12:00:00Z", "wet"),
        ]}
        onLog={() => undefined}
        onEdit={() => undefined}
      />
    ));

    expect(
      screen.getByRole("button", { name: "Edit care operation 1 from 2026-03-03" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Edit care operation 2 from 2026-03-03" }),
    ).toBeInTheDocument();
  });

  it("should identify an unrecognized persisted substrate component", () => {
    const id = substrateComponentId("10000000-0000-4000-8000-000000000099");
    expect(substrateComponentLabel(id)).toBe(id);
  });
});
