import { fireEvent, render, screen, waitFor, within } from "@solidjs/testing-library";
import { describe, expect, it, vi } from "vitest";
import { SubstrateCatalogForm } from "../../src/app/SubstrateCatalogForm";
import { nomenclatureInfo, nomenclatureName, substrateComponentId } from "../../src/domain/Journal";

const perliteId = substrateComponentId("00000000-0000-4000-8000-000000000003");
const perlite = {
  id: perliteId,
  data: { name: nomenclatureName("Perlite"), maybeInfo: null },
};

describe("SubstrateCatalogForm", () => {
  it("should validate additions and expose compact save actions", async () => {
    const onAdd = vi
      .fn()
      .mockResolvedValueOnce({ kind: "addFailed", reason: new Error("private") })
      .mockResolvedValueOnce({ kind: "added", entry: perlite });
    render(() => (
      <SubstrateCatalogForm
        components={[perlite]}
        onAdd={onAdd}
        onEdit={() => Promise.resolve({ kind: "edited", entry: perlite })}
        onClose={() => {
          return undefined;
        }}
      />
    ));

    const editForm = screen.getByRole("form", { name: "Edit Perlite" });
    expect(within(editForm).getByRole("button", { name: "Save Perlite" })).toHaveTextContent(
      "Save",
    );
    const addForm = screen.getByRole("form", { name: "Add substrate component" });
    fireEvent.submit(addForm);
    expect(screen.getByRole("alert")).toHaveTextContent("Enter a component name.");

    fireEvent.input(screen.getByRole("textbox", { name: "New substrate component name" }), {
      target: { value: " Pumice " },
    });
    fireEvent.input(screen.getByRole("textbox", { name: "New substrate component info" }), {
      target: { value: " Lightweight " },
    });
    fireEvent.submit(addForm);
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "The substrate component could not be saved.",
    );
    fireEvent.submit(addForm);
    await waitFor(() => {
      expect(onAdd).toHaveBeenCalledTimes(2);
    });
    expect(onAdd).toHaveBeenLastCalledWith({
      name: nomenclatureName("Pumice"),
      maybeInfo: nomenclatureInfo("Lightweight"),
    });
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "New substrate component name" })).toHaveValue("");
  });

  it("should validate edits and distinguish missing entries from save failures", async () => {
    const onEdit = vi
      .fn()
      .mockResolvedValueOnce({ kind: "recordMissing" })
      .mockResolvedValueOnce({ kind: "editFailed", reason: new Error("private") })
      .mockResolvedValueOnce({ kind: "edited", entry: perlite });
    let closed = false;
    render(() => (
      <SubstrateCatalogForm
        components={[perlite]}
        onAdd={() => Promise.resolve({ kind: "added", entry: perlite })}
        onEdit={onEdit}
        onClose={() => {
          closed = true;
        }}
      />
    ));

    const name = screen.getByRole("textbox", { name: "Name for Perlite" });
    const info = screen.getByRole("textbox", { name: "Info for Perlite" });
    const form = screen.getByRole("form", { name: "Edit Perlite" });
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
    fireEvent.input(info, { target: { value: " Small grain " } });
    fireEvent.submit(form);
    await waitFor(() => expect(screen.queryByRole("alert")).not.toBeInTheDocument());
    expect(onEdit).toHaveBeenLastCalledWith(perliteId, {
      name: nomenclatureName("Fine perlite"),
      maybeInfo: nomenclatureInfo("Small grain"),
    });

    fireEvent.click(screen.getByRole("button", { name: "Close substrate management" }));
    expect(closed).toBe(true);
  });
});
