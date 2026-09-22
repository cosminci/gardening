import { fireEvent, render, screen } from "@solidjs/testing-library";
import { expect, it, vi } from "vitest";
import { InfoControl } from "../../src/app/InfoControl";

it("should expose information through focus, touch activation, and Escape", () => {
  const escaped = vi.fn();
  render(() => (
    <div onKeyDown={escaped}>
      <InfoControl id="care-info" label="Information about care" notes="Apply weekly." />
    </div>
  ));

  const trigger = screen.getByRole("button", { name: "Information about care" });
  expect(trigger).toHaveAttribute("aria-describedby", "care-info");
  expect(screen.getByRole("tooltip")).toHaveTextContent("Apply weekly.");

  fireEvent.click(trigger);
  expect(trigger.parentElement).toHaveAttribute("data-open");

  trigger.focus();
  fireEvent.keyDown(trigger, { key: "Escape" });
  expect(trigger.parentElement).not.toHaveAttribute("data-open");
  expect(trigger).not.toHaveFocus();
  expect(escaped).not.toHaveBeenCalled();
});

it("should explain when no information was recorded", () => {
  render(() => <InfoControl id="empty-info" label="Information" notes={null} />);

  expect(screen.getByRole("tooltip")).toHaveTextContent("No notes.");
});
