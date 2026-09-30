import { render, screen } from "@solidjs/testing-library";
import { expect, it } from "vitest";
import { ActionIcons } from "../../src/app/ActionIcons";

it("should render an icon for each recorded action in a fixed order, skipping noAction", () => {
  render(() => (
    <ActionIcons
      actions={new Set(["pruned", "noAction", "watered", "showered", "fertilized", "pesticide"])}
    />
  ));

  const icons = screen.getAllByRole("img");
  expect(icons.map((icon) => icon.getAttribute("aria-label"))).toEqual([
    "Watered",
    "Showered",
    "Fertilized",
    "Pesticide",
    "Pruned",
  ]);
});

it("should render nothing when no actions were recorded", () => {
  render(() => <ActionIcons actions={new Set()} />);

  expect(screen.queryAllByRole("img")).toHaveLength(0);
});
