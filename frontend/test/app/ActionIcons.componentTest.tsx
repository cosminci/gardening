import { render, screen } from "@solidjs/testing-library";
import { expect, it } from "vitest";
import { ActionIcons } from "../../src/app/ActionIcons";

it("should render an icon for each recorded action in a fixed order, skipping noAction", () => {
  render(() => <ActionIcons actions={new Set(["pruned", "noAction", "watered", "fertilized"])} />);

  const icons = screen.getAllByRole("img");
  expect(icons.map((icon) => icon.getAttribute("aria-label"))).toEqual([
    "Watered",
    "Fertilized",
    "Pruned",
  ]);
});

it("should render nothing when no actions were recorded", () => {
  render(() => <ActionIcons actions={new Set()} />);

  expect(screen.queryAllByRole("img")).toHaveLength(0);
});

it("should render the pesticide glyph with its own class for styling", () => {
  render(() => <ActionIcons actions={new Set(["pesticide"])} />);

  const icon = screen.getByRole("img", { name: "Pesticide" });
  expect(icon).toHaveClass("action-icon", "action-icon--pesticide");
});
