import * as Testing from "@solidjs/testing-library";
import * as Vitest from "vitest";
import { PesticideEditor } from "../../src/app/PesticideEditor";
import { pesticideInfo, pesticideName, pesticideId } from "../../src/domain/Journal";

const neemId = pesticideId("00000000-0000-4000-8001-000000000003");
const neem = {
  id: neemId,
  data: {
    name: pesticideName("Neem oil"),
    type: "insecticide" as const,
    maybeInfo: pesticideInfo("Dilute before use.\nApply weekly."),
  },
  status: "active" as const,
};
const archivedNeem = { ...neem, status: "archived" as const };

Vitest.describe("PesticideEditor", () => {
  Vitest.it("should add a pesticide through constrained and multiline fields", async () => {
    const onAdd = Vitest.vi
      .fn()
      .mockResolvedValueOnce({ kind: "addFailed", reason: new Error("private") })
      .mockResolvedValueOnce({ kind: "added", entry: neem });
    const onClose = Vitest.vi.fn();
    Testing.render(() => (
      <PesticideEditor
        pesticide={undefined}
        onAdd={onAdd}
        onEdit={() => Promise.resolve({ kind: "edited", entry: neem })}
        onClose={onClose}
      />
    ));

    Vitest.expect(Testing.screen.getAllByRole("heading")).toHaveLength(1);
    Vitest.expect(
      Testing.screen.getByRole("heading", { name: "Add pesticide" }),
    ).toBeInTheDocument();
    const form = Testing.screen.getByRole("form", { name: "Add pesticide" });
    Testing.fireEvent.submit(form);
    Vitest.expect(Testing.screen.getByRole("alert")).toHaveTextContent("Enter a pesticide name.");
    Vitest.expect(Testing.screen.getByRole("combobox", { name: "Type" })).toHaveDisplayValue(
      "Fungicide",
    );
    Vitest.expect(
      Testing.within(Testing.screen.getByRole("combobox", { name: "Type" })).getAllByRole("option"),
    ).toHaveLength(3);

    Testing.fireEvent.input(Testing.screen.getByRole("textbox", { name: "Name" }), {
      target: { value: " Soap " },
    });
    Testing.fireEvent.change(Testing.screen.getByRole("combobox", { name: "Type" }), {
      target: { value: "insecticide" },
    });
    Testing.fireEvent.input(Testing.screen.getByRole("textbox", { name: "Info" }), {
      target: { value: " Dilute first.\nApply weekly. " },
    });
    Testing.fireEvent.submit(form);
    Vitest.expect(await Testing.screen.findByRole("alert")).toHaveTextContent(
      "The pesticide could not be saved.",
    );
    Testing.fireEvent.submit(form);
    await Testing.waitFor(() => {
      Vitest.expect(onClose).toHaveBeenCalledOnce();
    });
    Vitest.expect(onAdd).toHaveBeenLastCalledWith({
      name: pesticideName("Soap"),
      type: "insecticide",
      maybeInfo: pesticideInfo("Dilute first.\nApply weekly."),
    });
  });

  Vitest.it(
    "should edit a pesticide and distinguish missing entries from save failures",
    async () => {
      const onEdit = Vitest.vi
        .fn()
        .mockResolvedValueOnce({ kind: "pesticideMissing" })
        .mockResolvedValueOnce({ kind: "editFailed", reason: new Error("private") })
        .mockResolvedValueOnce({ kind: "edited", entry: neem });
      const onClose = Vitest.vi.fn();
      Testing.render(() => (
        <PesticideEditor
          pesticide={neem}
          onAdd={() => Promise.resolve({ kind: "added", entry: neem })}
          onEdit={onEdit}
          onClose={onClose}
        />
      ));

      const form = Testing.screen.getByRole("form", { name: "Edit Neem oil" });
      const name = Testing.screen.getByRole("textbox", { name: "Name" });
      Testing.fireEvent.input(name, { target: { value: " " } });
      Testing.fireEvent.submit(form);
      Vitest.expect(Testing.screen.getByRole("alert")).toHaveTextContent("Enter a pesticide name.");

      Testing.fireEvent.input(name, { target: { value: " Neem concentrate " } });
      Testing.fireEvent.change(Testing.screen.getByRole("combobox", { name: "Type" }), {
        target: { value: "treatment" },
      });
      Testing.fireEvent.submit(form);
      Vitest.expect(await Testing.screen.findByRole("alert")).toHaveTextContent(
        "This pesticide no longer exists.",
      );
      Testing.fireEvent.submit(form);
      await Testing.waitFor(() =>
        Vitest.expect(Testing.screen.getByRole("alert")).toHaveTextContent(
          "The pesticide could not be saved.",
        ),
      );
      Testing.fireEvent.input(Testing.screen.getByRole("textbox", { name: "Info" }), {
        target: { value: " Use weekly. " },
      });
      Testing.fireEvent.submit(form);
      await Testing.waitFor(() => {
        Vitest.expect(onClose).toHaveBeenCalledOnce();
      });
      Vitest.expect(onEdit).toHaveBeenLastCalledWith(neemId, {
        name: pesticideName("Neem concentrate"),
        type: "treatment",
        maybeInfo: pesticideInfo("Use weekly."),
      });
    },
  );

  Vitest.it(
    "should reject saving an edit rejected because the pesticide became archived",
    async () => {
      const onEdit = Vitest.vi.fn().mockResolvedValueOnce({ kind: "pesticideArchived" });
      Testing.render(() => (
        <PesticideEditor
          pesticide={neem}
          onAdd={() => Promise.resolve({ kind: "added", entry: neem })}
          onEdit={onEdit}
          onClose={Vitest.vi.fn()}
        />
      ));

      Testing.fireEvent.submit(Testing.screen.getByRole("form", { name: "Edit Neem oil" }));

      Vitest.expect(await Testing.screen.findByRole("alert")).toHaveTextContent(
        "This pesticide is archived and can no longer be edited.",
      );
    },
  );

  Vitest.it("should offer an archive action beside save for an active existing pesticide", () => {
    const onArchive = { controlId: "archive-pesticide-1", onClick: Vitest.vi.fn() };
    Testing.render(() => (
      <PesticideEditor
        pesticide={neem}
        onAdd={() => Promise.resolve({ kind: "added", entry: neem })}
        onEdit={() => Promise.resolve({ kind: "edited", entry: neem })}
        onArchive={onArchive}
        onClose={Vitest.vi.fn()}
      />
    ));

    const archiveButton = Testing.screen.getByRole("button", { name: "Archive" });
    const saveButton = Testing.screen.getByRole("button", { name: "Save" });
    Testing.fireEvent.click(archiveButton);

    Vitest.expect(
      archiveButton.compareDocumentPosition(saveButton) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    Vitest.expect(onArchive.onClick).toHaveBeenCalledOnce();
  });

  Vitest.it(
    "should show an archived pesticide's status with no save or archive action and disabled fields",
    () => {
      Testing.render(() => (
        <PesticideEditor
          pesticide={archivedNeem}
          onAdd={() => Promise.resolve({ kind: "added", entry: neem })}
          onEdit={() => Promise.resolve({ kind: "edited", entry: neem })}
          onArchive={{ controlId: "archive-pesticide-1", onClick: Vitest.vi.fn() }}
          onClose={Vitest.vi.fn()}
        />
      ));

      Vitest.expect(Testing.screen.getByText("Archived")).toBeInTheDocument();
      Vitest.expect(Testing.screen.queryByRole("button", { name: "Save" })).not.toBeInTheDocument();
      Vitest.expect(
        Testing.screen.queryByRole("button", { name: "Archive" }),
      ).not.toBeInTheDocument();
      Vitest.expect(Testing.screen.getByRole("textbox", { name: "Name" })).toBeDisabled();
      Vitest.expect(Testing.screen.getByRole("combobox", { name: "Type" })).toBeDisabled();
      Vitest.expect(Testing.screen.getByRole("textbox", { name: "Info" })).toBeDisabled();
    },
  );

  Vitest.it("should prevent another save while a save is in progress", async () => {
    let finishSaving!: (result: { kind: "added"; entry: typeof neem }) => void;
    const saving = new Promise<{ kind: "added"; entry: typeof neem }>((resolve) => {
      finishSaving = resolve;
    });
    const onAdd = Vitest.vi.fn(() => saving);
    const onClose = Vitest.vi.fn();
    Testing.render(() => (
      <PesticideEditor
        pesticide={undefined}
        onAdd={onAdd}
        onEdit={() => Promise.resolve({ kind: "edited", entry: neem })}
        onClose={onClose}
      />
    ));

    Testing.fireEvent.input(Testing.screen.getByRole("textbox", { name: "Name" }), {
      target: { value: "Soap" },
    });
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Save" }));
    const savingButton = Testing.screen.getByRole("button", { name: "Saving…" });
    Vitest.expect(savingButton).toBeDisabled();
    Testing.fireEvent.click(savingButton);
    Vitest.expect(onAdd).toHaveBeenCalledOnce();

    finishSaving({ kind: "added", entry: neem });
    await Testing.waitFor(() => {
      Vitest.expect(onClose).toHaveBeenCalledOnce();
    });
  });

  Vitest.it("should collapse without saving", () => {
    const onClose = Vitest.vi.fn();
    Testing.render(() => (
      <PesticideEditor
        pesticide={neem}
        onAdd={() => Promise.resolve({ kind: "added", entry: neem })}
        onEdit={() => Promise.resolve({ kind: "edited", entry: neem })}
        onClose={onClose}
      />
    ));

    Testing.fireEvent.click(
      Testing.screen.getByRole("button", { name: "Collapse pesticide editor" }),
    );
    Vitest.expect(onClose).toHaveBeenCalledOnce();
  });
});
