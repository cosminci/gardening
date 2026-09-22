import { render, screen } from "@solidjs/testing-library";
import { describe, expect, it } from "vitest";
import { pesticideLabel, substrateComponentLabel } from "../../src/app/JournalLabels";
import { PlantCard } from "../../src/app/PlantCard";
import { nomenclatureName, pesticideId, substrateComponentId } from "../../src/domain/Journal";
import { care, ficus } from "./JournalTestSupport";

describe("plant operation controls", () => {
  it("should distinguish edit controls for same-day care operations", () => {
    const componentId = substrateComponentId("00000000-0000-4000-8000-000000000003");
    render(() => (
      <PlantCard
        plant={ficus()}
        operations={[
          care({ id: "o1", date: "2026-03-03T08:00:00Z", moisture: "dry" }),
          care({ id: "o2", date: "2026-03-03T12:00:00Z", moisture: "wet" }),
        ]}
        substrateComponents={[
          {
            id: componentId,
            data: { name: nomenclatureName("Perlite"), maybeInfo: null },
          },
        ]}
        pesticides={[]}
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
    expect(substrateComponentLabel(id, [])).toBe(id);
  });

  it("should identify an unrecognized persisted pesticide", () => {
    const id = pesticideId("10000000-0000-4000-8001-000000000099");
    expect(pesticideLabel(id, [])).toBe(id);
  });
});
