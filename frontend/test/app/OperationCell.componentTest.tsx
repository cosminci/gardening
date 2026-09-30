import { render, screen } from "@solidjs/testing-library";
import { expect, it } from "vitest";
import { OperationCell } from "../../src/app/OperationCell";
import * as Journal from "../../src/domain/Journal";
import { care, repot } from "./JournalTestSupport";

const neemId = Journal.pesticideId("00000000-0000-4000-8002-000000000001");
const neem: Journal.Pesticide = {
  id: neemId,
  data: { name: Journal.pesticideName("Neem oil"), type: "insecticide", maybeInfo: null },
  status: "active",
};

it("should render both a short and long date span, with the long form in the accessible label", () => {
  render(() => (
    <OperationCell
      operation={care({ id: "o1", date: "2026-03-03T00:00:00Z", moisture: "wet" })}
      position={1}
      section="recent"
      substrateComponents={[]}
      pesticides={[]}
      onEdit={() => undefined}
    />
  ));

  expect(screen.getByText("3rd of March")).toBeInTheDocument();
  expect(screen.getByText("03.03")).toBeInTheDocument();
  expect(
    screen.getByRole("button", { name: "Edit recent care operation 1 from 3rd of March" }),
  ).toBeInTheDocument();
});

it("should state historical, not recent, in the edit label when rendered in the historical section", () => {
  render(() => (
    <OperationCell
      operation={care({ id: "o1", date: "2026-03-03T00:00:00Z", moisture: "wet" })}
      position={2}
      section="historical"
      substrateComponents={[]}
      pesticides={[]}
      onEdit={() => undefined}
    />
  ));

  expect(
    screen.getByRole("button", { name: "Edit historical care operation 2 from 03.03.2026" }),
  ).toBeInTheDocument();
});

it("should render a logged note without a Note label", () => {
  render(() => (
    <OperationCell
      operation={care({
        id: "o1",
        date: "2026-03-03T00:00:00Z",
        moisture: "wet",
        maybeNote: "Recovering well",
      })}
      position={1}
      section="recent"
      substrateComponents={[]}
      pesticides={[]}
      onEdit={() => undefined}
    />
  ));

  expect(screen.getByText("Recovering well")).toBeInTheDocument();
  expect(screen.queryByText("Note")).not.toBeInTheDocument();
});

it("should render an empty action-icon row when no actions were recorded", () => {
  render(() => (
    <OperationCell
      operation={care({
        id: "o1",
        date: "2026-03-03T00:00:00Z",
        moisture: "wet",
        actions: new Set(),
      })}
      position={1}
      section="recent"
      substrateComponents={[]}
      pesticides={[]}
      onEdit={() => undefined}
    />
  ));

  expect(
    screen.queryAllByRole("img", { name: /Watered|Showered|Fertilized|Pesticide|Pruned/ }),
  ).toEqual([]);
  expect(screen.getByText("None recorded")).toBeInTheDocument();
});

it("should show the applied pesticide's name in a popup on the pesticide icon", () => {
  render(() => (
    <OperationCell
      operation={care({
        id: "o1",
        date: "2026-03-03T00:00:00Z",
        moisture: "wet",
        actions: new Set(["pesticide"]),
        pesticides: new Set([neemId]),
      })}
      position={1}
      section="recent"
      substrateComponents={[]}
      pesticides={[neem]}
      onEdit={() => undefined}
    />
  ));

  screen.getByRole("button", { name: "Pesticides applied" });
  expect(screen.getByRole("tooltip")).toHaveTextContent("Neem oil");
});

it("should fall back to no notes when a pesticide action carries no specific pesticide", () => {
  render(() => (
    <OperationCell
      operation={care({
        id: "o1",
        date: "2026-03-03T00:00:00Z",
        moisture: "wet",
        actions: new Set(["pesticide"]),
        pesticides: new Set(),
      })}
      position={1}
      section="recent"
      substrateComponents={[]}
      pesticides={[]}
      onEdit={() => undefined}
    />
  ));

  expect(screen.getByRole("tooltip")).toHaveTextContent("No notes.");
});

it("should render only the substrate row for a repot operation, with no moisture gauge or action icons", () => {
  render(() => (
    <OperationCell
      operation={repot("o1", "2026-03-03T00:00:00Z")}
      position={1}
      section="recent"
      substrateComponents={[
        {
          id: Journal.substrateComponentId("00000000-0000-4000-8000-000000000003"),
          data: { name: Journal.substrateComponentName("Perlite"), maybeInfo: null },
          status: "active",
        },
      ]}
      pesticides={[]}
      onEdit={() => undefined}
    />
  ));

  expect(screen.getByText("Substrate")).toBeInTheDocument();
  expect(screen.queryByRole("img", { name: "Wet" })).not.toBeInTheDocument();
  expect(
    screen.queryAllByRole("img", { name: /Watered|Showered|Fertilized|Pesticide|Pruned/ }),
  ).toEqual([]);
});
