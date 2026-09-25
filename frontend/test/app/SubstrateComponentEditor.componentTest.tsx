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
};

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
        .mockResolvedValueOnce({ kind: "recordMissing" })
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
