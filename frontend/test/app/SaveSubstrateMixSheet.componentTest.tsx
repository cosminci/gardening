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

  Vitest.it(
    "should save with trimmed name and notes, then close; sends null notes when blank",
    async () => {
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
      Testing.cleanup();

      // Blank Notes → null
      const onSave2 = Vitest.vi.fn().mockResolvedValue({ kind: "added", entry: mix });
      Testing.render(() => <SaveSubstrateMixSheet onSave={onSave2} onClose={() => undefined} />);
      Testing.fireEvent.input(Testing.screen.getByRole("textbox", { name: "Name" }), {
        target: { value: "Standard mix" },
      });
      Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Save" }));
      await Testing.waitFor(() => {
        Vitest.expect(onSave2).toHaveBeenCalledWith(Journal.substrateMixName("Standard mix"), null);
      });
    },
  );

  Vitest.it.each([
    {
      result: { kind: "duplicateSubstrate" as const },
      expected: "A mix with these exact components and shares is already saved.",
    },
    {
      result: { kind: "addFailed" as const, reason: new Error("offline") },
      expected: "The mix could not be saved.",
    },
  ])("should show $result.kind error without closing", async ({ result, expected }) => {
    const onSave = Vitest.vi.fn().mockResolvedValue(result);
    const onClose = Vitest.vi.fn();
    Testing.render(() => <SaveSubstrateMixSheet onSave={onSave} onClose={onClose} />);

    Testing.fireEvent.input(Testing.screen.getByRole("textbox", { name: "Name" }), {
      target: { value: "Standard mix" },
    });
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Save" }));

    Vitest.expect(await Testing.screen.findByRole("alert")).toHaveTextContent(expected);
    Vitest.expect(onClose).not.toHaveBeenCalled();
  });

  Vitest.it("should prevent another save while a save is in progress", async () => {
    let finishSaving!: (result: Journal.AddSubstrateMixResult) => void;
    const saving = new Promise<Journal.AddSubstrateMixResult>((resolve) => {
      finishSaving = resolve;
    });
    const onSave = Vitest.vi.fn(() => saving);
    const onClose = Vitest.vi.fn();
    Testing.render(() => <SaveSubstrateMixSheet onSave={onSave} onClose={onClose} />);

    Testing.fireEvent.input(Testing.screen.getByRole("textbox", { name: "Name" }), {
      target: { value: "Standard mix" },
    });
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Save" }));
    const savingButton = Testing.screen.getByRole("button", { name: "Saving…" });
    Vitest.expect(savingButton).toBeDisabled();
    Testing.fireEvent.click(savingButton);
    Vitest.expect(onSave).toHaveBeenCalledOnce();

    finishSaving({ kind: "added", entry: mix });
    await Testing.waitFor(() => {
      Vitest.expect(onClose).toHaveBeenCalledOnce();
    });
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
