import { fireEvent, render, screen, waitFor } from "@solidjs/testing-library";
import { describe, expect, it, vi } from "vitest";
import { SubstrateComponentEditor } from "../../src/app/SubstrateComponentEditor";
import { nomenclatureInfo, nomenclatureName, substrateComponentId } from "../../src/domain/Journal";

const perliteId = substrateComponentId("00000000-0000-4000-8000-000000000003");
const perlite = {
  id: perliteId,
  data: {
    name: nomenclatureName("Perlite"),
    maybeInfo: nomenclatureInfo("Improves drainage.\nUse up to 30%."),
  },
};

describe("SubstrateComponentEditor", () => {
  it("should add a component with multiline information", async () => {
    const onAdd = vi
      .fn()
      .mockResolvedValueOnce({ kind: "addFailed", reason: new Error("private") })
      .mockResolvedValueOnce({ kind: "added", entry: perlite });
    const onClose = vi.fn();
    render(() => (
      <SubstrateComponentEditor
        component={undefined}
        onAdd={onAdd}
        onEdit={() => Promise.resolve({ kind: "edited", entry: perlite })}
        onClose={onClose}
      />
    ));

    expect(screen.getAllByRole("heading")).toHaveLength(1);
    expect(screen.getByRole("heading", { name: "Add substrate component" })).toBeInTheDocument();
    const form = screen.getByRole("form", { name: "Add substrate component" });
    fireEvent.submit(form);
    expect(screen.getByRole("alert")).toHaveTextContent("Enter a component name.");
    fireEvent.input(screen.getByRole("textbox", { name: "Name" }), {
      target: { value: " Pumice " },
    });
    fireEvent.input(screen.getByRole("textbox", { name: "Info" }), {
      target: { value: " Lightweight.\nRinse first. " },
    });
    fireEvent.submit(form);
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "The substrate component could not be saved.",
    );
    fireEvent.submit(form);
    await waitFor(() => {
      expect(onClose).toHaveBeenCalledOnce();
    });
    expect(onAdd).toHaveBeenLastCalledWith({
      name: nomenclatureName("Pumice"),
      maybeInfo: nomenclatureInfo("Lightweight.\nRinse first."),
    });
  });

  it("should edit a component and distinguish missing entries from save failures", async () => {
    const onEdit = vi
      .fn()
      .mockResolvedValueOnce({ kind: "recordMissing" })
      .mockResolvedValueOnce({ kind: "editFailed", reason: new Error("private") })
      .mockResolvedValueOnce({ kind: "edited", entry: perlite });
    const onClose = vi.fn();
    render(() => (
      <SubstrateComponentEditor
        component={perlite}
        onAdd={() => Promise.resolve({ kind: "added", entry: perlite })}
        onEdit={onEdit}
        onClose={onClose}
      />
    ));

    const form = screen.getByRole("form", { name: "Edit Perlite" });
    const name = screen.getByRole("textbox", { name: "Name" });
    fireEvent.input(name, { target: { value: " " } });
    fireEvent.submit(form);
    expect(screen.getByRole("alert")).toHaveTextContent("Enter a component name.");
    fireEvent.input(name, { target: { value: " Fine perlite " } });
    fireEvent.submit(form);
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "This substrate component no longer exists.",
    );
    fireEvent.submit(form);
    await waitFor(() =>
      expect(screen.getByRole("alert")).toHaveTextContent(
        "The substrate component could not be saved.",
      ),
    );
    fireEvent.input(screen.getByRole("textbox", { name: "Info" }), {
      target: { value: " Small grain. " },
    });
    fireEvent.submit(form);
    await waitFor(() => {
      expect(onClose).toHaveBeenCalledOnce();
    });
    expect(onEdit).toHaveBeenLastCalledWith(perliteId, {
      name: nomenclatureName("Fine perlite"),
      maybeInfo: nomenclatureInfo("Small grain."),
    });
  });

  it("should collapse without saving", () => {
    const onClose = vi.fn();
    render(() => (
      <SubstrateComponentEditor
        component={perlite}
        onAdd={() => Promise.resolve({ kind: "added", entry: perlite })}
        onEdit={() => Promise.resolve({ kind: "edited", entry: perlite })}
        onClose={onClose}
      />
    ));

    fireEvent.click(screen.getByRole("button", { name: "Collapse substrate editor" }));
    expect(onClose).toHaveBeenCalledOnce();
  });
});
