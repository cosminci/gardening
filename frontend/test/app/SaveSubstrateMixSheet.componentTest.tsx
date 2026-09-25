import * as Testing from "@solidjs/testing-library";
import * as Vitest from "vitest";
import { SaveSubstrateMixSheet } from "../../src/app/SaveSubstrateMixSheet";
import * as Journal from "../../src/domain/Journal";

const mix: Journal.SubstrateMix = {
  id: Journal.substrateMixId("00000000-0000-4000-8000-000000000009"),
  name: Journal.substrateMixName("Standard mix"),
  maybeNotes: null,
  substrate: Journal.substrate([
    {
      component: Journal.substrateComponentId("00000000-0000-4000-8000-000000000003"),
      share: Journal.percentage(100),
    },
  ]),
};

Vitest.describe("SaveSubstrateMixSheet", () => {
  Vitest.it("should focus the panel on mount and reject a blank name", () => {
    const onSave = Vitest.vi.fn();
    Testing.render(() => <SaveSubstrateMixSheet onSave={onSave} onClose={() => undefined} />);

    Vitest.expect(Testing.screen.getByRole("region", { name: "Save substrate mix" })).toHaveFocus();
    const form = Testing.screen.getByRole("form", { name: "Save substrate mix" });
    Testing.fireEvent.submit(form);
    Vitest.expect(Testing.screen.getByRole("alert")).toHaveTextContent(
      "Enter a name for this mix.",
    );
    Vitest.expect(onSave).not.toHaveBeenCalled();
  });

  Vitest.it("should save with trimmed name and notes, then close", async () => {
    const onSave = Vitest.vi.fn().mockResolvedValue({ kind: "added", entry: mix });
    const onClose = Vitest.vi.fn();
    Testing.render(() => <SaveSubstrateMixSheet onSave={onSave} onClose={onClose} />);

    Testing.fireEvent.input(Testing.screen.getByRole("textbox", { name: "Name" }), {
      target: { value: " Standard mix " },
    });
    Testing.fireEvent.input(Testing.screen.getByRole("textbox", { name: "Notes" }), {
      target: { value: " Works well for aroids " },
    });
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Save" }));

    await Testing.waitFor(() => {
      Vitest.expect(onClose).toHaveBeenCalledOnce();
    });
    Vitest.expect(onSave).toHaveBeenCalledWith(
      Journal.substrateMixName("Standard mix"),
      Journal.substrateMixNotes("Works well for aroids"),
    );
  });

  Vitest.it("should save with null notes when notes are blank", async () => {
    const onSave = Vitest.vi.fn().mockResolvedValue({ kind: "added", entry: mix });
    Testing.render(() => <SaveSubstrateMixSheet onSave={onSave} onClose={() => undefined} />);

    Testing.fireEvent.input(Testing.screen.getByRole("textbox", { name: "Name" }), {
      target: { value: "Standard mix" },
    });
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Save" }));

    await Testing.waitFor(() => {
      Vitest.expect(onSave).toHaveBeenCalledWith(Journal.substrateMixName("Standard mix"), null);
    });
  });

  Vitest.it("should show a duplicate-substrate error without closing", async () => {
    const onSave = Vitest.vi.fn().mockResolvedValue({ kind: "duplicateSubstrate" });
    const onClose = Vitest.vi.fn();
    Testing.render(() => <SaveSubstrateMixSheet onSave={onSave} onClose={onClose} />);

    Testing.fireEvent.input(Testing.screen.getByRole("textbox", { name: "Name" }), {
      target: { value: "Standard mix" },
    });
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Save" }));

    Vitest.expect(await Testing.screen.findByRole("alert")).toHaveTextContent(
      "A mix with these exact components and shares is already saved.",
    );
    Vitest.expect(onClose).not.toHaveBeenCalled();
  });

  Vitest.it("should show a generic save-failure error without closing", async () => {
    const onSave = Vitest.vi
      .fn()
      .mockResolvedValue({ kind: "addFailed", reason: new Error("offline") });
    const onClose = Vitest.vi.fn();
    Testing.render(() => <SaveSubstrateMixSheet onSave={onSave} onClose={onClose} />);

    Testing.fireEvent.input(Testing.screen.getByRole("textbox", { name: "Name" }), {
      target: { value: "Standard mix" },
    });
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Save" }));

    Vitest.expect(await Testing.screen.findByRole("alert")).toHaveTextContent(
      "The mix could not be saved.",
    );
    Vitest.expect(onClose).not.toHaveBeenCalled();
  });

  Vitest.it("should collapse without saving", () => {
    const onClose = Vitest.vi.fn();
    Testing.render(() => (
      <SaveSubstrateMixSheet
        onSave={() => Promise.resolve({ kind: "addFailed", reason: new Error("offline") })}
        onClose={onClose}
      />
    ));

    Testing.fireEvent.click(
      Testing.screen.getByRole("button", { name: "Collapse save mix editor" }),
    );
    Vitest.expect(onClose).toHaveBeenCalledOnce();
  });
});
