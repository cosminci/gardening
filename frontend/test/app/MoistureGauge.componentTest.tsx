import { render, screen } from "@solidjs/testing-library";
import { expect, it } from "vitest";
import { MoistureGauge } from "../../src/app/MoistureGauge";

it("should fully fill the gauge for a wet reading", () => {
  render(() => <MoistureGauge level="wet" />);

  const gauge = screen.getByRole("img", { name: "Wet" });
  expect(gauge.querySelector(".moisture-gauge__fill")).toBeInTheDocument();
});

it("should scale the fill proportionally for a moderate reading", () => {
  render(() => <MoistureGauge level="moderatePlus" />);

  const gauge = screen.getByRole("img", { name: "Moderate +" });
  expect(gauge.querySelector(".moisture-gauge__fill")).toBeInTheDocument();
});

it("should render a dashed, unfilled outline when there is no reading", () => {
  render(() => <MoistureGauge level="noReading" />);

  const gauge = screen.getByRole("img", { name: "N/A" });
  expect(gauge.querySelector(".moisture-gauge__fill")).not.toBeInTheDocument();
});

it("should render a solid, empty outline for a known-dry reading, unlike an unknown reading", () => {
  render(() => <MoistureGauge level="dry" />);

  const gauge = screen.getByRole("img", { name: "Dry" });
  expect(gauge.querySelector(".moisture-gauge__fill")).toHaveAttribute("height", "0");
});
