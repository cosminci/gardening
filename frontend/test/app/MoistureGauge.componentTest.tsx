import { render, screen } from "@solidjs/testing-library";
import { expect, it } from "vitest";
import { MoistureGauge } from "../../src/app/MoistureGauge";

// The droplet's fillable span runs from y=3 (top) to y=23 (bottom) — an 20-unit travel.
const dropletTravel = 20;
const dropletBottom = 23;

it("should fully fill the gauge for a wet reading", () => {
  render(() => <MoistureGauge level="wet" />);

  const gauge = screen.getByRole("img", { name: "Wet" });
  const fill = gauge.querySelector(".moisture-gauge__fill");
  const expectedFillHeight = dropletTravel;
  const expectedFillTop = dropletBottom - expectedFillHeight;
  expect(fill).toHaveAttribute("height", String(expectedFillHeight));
  expect(fill).toHaveAttribute("y", String(expectedFillTop));
});

it("should scale the fill proportionally for a moderate reading", () => {
  render(() => <MoistureGauge level="moderatePlus" />);

  const gauge = screen.getByRole("img", { name: "Moderate +" });
  const fill = gauge.querySelector(".moisture-gauge__fill");
  const expectedFillHeight = dropletTravel * 0.67;
  const expectedFillTop = dropletBottom - expectedFillHeight;
  expect(Number(fill?.getAttribute("height"))).toBeCloseTo(expectedFillHeight);
  expect(Number(fill?.getAttribute("y"))).toBeCloseTo(expectedFillTop);
});

it("should render a dashed, unfilled outline when there is no reading", () => {
  render(() => <MoistureGauge level="noReading" />);

  const gauge = screen.getByRole("img", { name: "N/A" });
  expect(gauge).toHaveClass("moisture-gauge--unknown");
  expect(gauge.querySelector(".moisture-gauge__fill")).not.toBeInTheDocument();
});
