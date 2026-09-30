import * as Testing from "@solidjs/testing-library";
import * as Vitest from "vitest";
import { SubstrateComponentEditor } from "../../src/app/SubstrateComponentEditor";
import {
  substrateComponentInfo,
  substrateComponentName,
  substrateComponentId,
} from "../../src/domain/Journal";

const perliteId = substrateComponentId("00000000-0000-4000-8000-000000000003");
const perlite = {
  id: perliteId,
  data: {
    name: substrateComponentName("Perlite"),
    maybeInfo: substrateComponentInfo("Improves drainage.\nUse up to 30%."),
  },
  status: "active" as const,
};
const archivedPerlite = { ...perlite, status: "archived" as const };

Vitest.describe("SubstrateComponentEditor", () => {
  Vitest.it("should add a component with multiline information", async () => {
    const onAdd = Vitest.vi
      .fn()
      .mockResolvedValueOnce({ kind: "addFailed", reason: new Error("private") })
      .mockResolvedValueOnce({ kind: "added", entry: perlite });
    const onClose = Vitest.vi.fn();
    Testing.render(() => (
      <SubstrateComponentEditor
        component={undefined}
        onAdd={onAdd}
        onEdit={() => Promise.resolve({ kind: "edited", entry: perlite })}
        onClose={onClose}
      />
    ));

    Vitest.expect(Testing.screen.getAllByRole("heading")).toHaveLength(1);
    Vitest.expect(
      Testing.screen.getByRole("heading", { name: "Add substrate component" }),
    ).toBeInTheDocument();
    const form = Testing.screen.getByRole("form", { name: "Add substrate component" });
    Testing.fireEvent.submit(form);
    Vitest.expect(Testing.screen.getByRole("alert")).toHaveTextContent("Enter a component name.");
    Testing.fireEvent.input(Testing.screen.getByRole("textbox", { name: "Name" }), {
      target: { value: " Pumice " },
    });
    Testing.fireEvent.input(Testing.screen.getByRole("textbox", { name: "Info" }), {
      target: { value: " Lightweight.\nRinse first. " },
    });
    Testing.fireEvent.submit(form);
    Vitest.expect(await Testing.screen.findByRole("alert")).toHaveTextContent(
      "The substrate component could not be saved.",
    );
    Testing.fireEvent.submit(form);
    await Testing.waitFor(() => {
      Vitest.expect(onClose).toHaveBeenCalledOnce();
    });
    Vitest.expect(onAdd).toHaveBeenLastCalledWith({
      name: substrateComponentName("Pumice"),
      maybeInfo: substrateComponentInfo("Lightweight.\nRinse first."),
    });
  });

  Vitest.it(
    "should edit a component and distinguish missing entries from save failures",
    async () => {
      const onEdit = Vitest.vi
        .fn()
        .mockResolvedValueOnce({ kind: "componentMissing" })
        .mockResolvedValueOnce({ kind: "editFailed", reason: new Error("private") })
        .mockResolvedValueOnce({ kind: "edited", entry: perlite });
      const onClose = Vitest.vi.fn();
      Testing.render(() => (
        <SubstrateComponentEditor
          component={perlite}
          onAdd={() => Promise.resolve({ kind: "added", entry: perlite })}
          onEdit={onEdit}
          onClose={onClose}
        />
      ));

      const form = Testing.screen.getByRole("form", { name: "Edit Perlite" });
      const name = Testing.screen.getByRole("textbox", { name: "Name" });
      Testing.fireEvent.input(name, { target: { value: " " } });
      Testing.fireEvent.submit(form);
      Vitest.expect(Testing.screen.getByRole("alert")).toHaveTextContent("Enter a component name.");
      Testing.fireEvent.input(name, { target: { value: " Fine perlite " } });
      Testing.fireEvent.submit(form);
      Vitest.expect(await Testing.screen.findByRole("alert")).toHaveTextContent(
        "This substrate component no longer exists.",
      );
      Testing.fireEvent.submit(form);
      await Testing.waitFor(() =>
        Vitest.expect(Testing.screen.getByRole("alert")).toHaveTextContent(
          "The substrate component could not be saved.",
        ),
      );
      Testing.fireEvent.input(Testing.screen.getByRole("textbox", { name: "Info" }), {
        target: { value: " Small grain. " },
      });
      Testing.fireEvent.submit(form);
      await Testing.waitFor(() => {
        Vitest.expect(onClose).toHaveBeenCalledOnce();
      });
      Vitest.expect(onEdit).toHaveBeenLastCalledWith(perliteId, {
        name: substrateComponentName("Fine perlite"),
        maybeInfo: substrateComponentInfo("Small grain."),
      });
    },
  );

  Vitest.it(
    "should reject saving an edit rejected because the component became archived",
    async () => {
      const onEdit = Vitest.vi.fn().mockResolvedValueOnce({ kind: "componentArchived" });
      Testing.render(() => (
        <SubstrateComponentEditor
          component={perlite}
          onAdd={() => Promise.resolve({ kind: "added", entry: perlite })}
          onEdit={onEdit}
          onClose={Vitest.vi.fn()}
        />
      ));

      Testing.fireEvent.submit(Testing.screen.getByRole("form", { name: "Edit Perlite" }));

      Vitest.expect(await Testing.screen.findByRole("alert")).toHaveTextContent(
        "This substrate component is archived and can no longer be edited.",
      );
    },
  );

  Vitest.it("should offer an archive action beside save for an active existing component", () => {
    const onArchive = { controlId: "archive-substrate-component-1", onClick: Vitest.vi.fn() };
    Testing.render(() => (
      <SubstrateComponentEditor
        component={perlite}
        onAdd={() => Promise.resolve({ kind: "added", entry: perlite })}
        onEdit={() => Promise.resolve({ kind: "edited", entry: perlite })}
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
    "should show an archived component's status with no save or archive action and disabled fields",
    () => {
      Testing.render(() => (
        <SubstrateComponentEditor
          component={archivedPerlite}
          onAdd={() => Promise.resolve({ kind: "added", entry: perlite })}
          onEdit={() => Promise.resolve({ kind: "edited", entry: perlite })}
          onArchive={{ controlId: "archive-substrate-component-1", onClick: Vitest.vi.fn() }}
          onClose={Vitest.vi.fn()}
        />
      ));

      Vitest.expect(Testing.screen.getByText("Archived")).toBeInTheDocument();
      Vitest.expect(Testing.screen.queryByRole("button", { name: "Save" })).not.toBeInTheDocument();
      Vitest.expect(
        Testing.screen.queryByRole("button", { name: "Archive" }),
      ).not.toBeInTheDocument();
      Vitest.expect(Testing.screen.getByRole("textbox", { name: "Name" })).toBeDisabled();
      Vitest.expect(Testing.screen.getByRole("textbox", { name: "Info" })).toBeDisabled();
    },
  );

  Vitest.it("should prevent another save while a save is in progress", async () => {
    let finishSaving!: (result: { kind: "added"; entry: typeof perlite }) => void;
    const saving = new Promise<{ kind: "added"; entry: typeof perlite }>((resolve) => {
      finishSaving = resolve;
    });
    const onAdd = Vitest.vi.fn(() => saving);
    const onClose = Vitest.vi.fn();
    Testing.render(() => (
      <SubstrateComponentEditor
        component={undefined}
        onAdd={onAdd}
        onEdit={() => Promise.resolve({ kind: "edited", entry: perlite })}
        onClose={onClose}
      />
    ));

    Testing.fireEvent.input(Testing.screen.getByRole("textbox", { name: "Name" }), {
      target: { value: "Pumice" },
    });
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Save" }));
    const savingButton = Testing.screen.getByRole("button", { name: "Saving…" });
    Vitest.expect(savingButton).toBeDisabled();
    Testing.fireEvent.click(savingButton);
    Vitest.expect(onAdd).toHaveBeenCalledOnce();

    finishSaving({ kind: "added", entry: perlite });
    await Testing.waitFor(() => {
      Vitest.expect(onClose).toHaveBeenCalledOnce();
    });
  });

  Vitest.it("should collapse without saving", () => {
    const onClose = Vitest.vi.fn();
    Testing.render(() => (
      <SubstrateComponentEditor
        component={perlite}
        onAdd={() => Promise.resolve({ kind: "added", entry: perlite })}
        onEdit={() => Promise.resolve({ kind: "edited", entry: perlite })}
        onClose={onClose}
      />
    ));

    Testing.fireEvent.click(
      Testing.screen.getByRole("button", { name: "Collapse substrate editor" }),
    );
    Vitest.expect(onClose).toHaveBeenCalledOnce();
  });
});
