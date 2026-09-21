import { fireEvent, render, screen, waitFor, within } from "@solidjs/testing-library";
import { describe, expect, it, vi } from "vitest";
import { PesticideCatalog, PesticideCatalogEditor } from "../../src/app/PesticideCatalog";
import { nomenclatureInfo, nomenclatureName, pesticideId } from "../../src/domain/Journal";

const neemId = pesticideId("00000000-0000-4000-8001-000000000003");
const neem = {
  id: neemId,
  data: {
    name: nomenclatureName("Neem oil"),
    pesticideType: "insecticide" as const,
    maybeInfo: nomenclatureInfo("Dilute before use.\nApply weekly."),
  },
};

describe("PesticideCatalog", () => {
  it("should list pesticides read-only and request nested editors", () => {
    const onAdd = vi.fn();
    const onEdit = vi.fn();
    const onClose = vi.fn();
    render(() => (
      <PesticideCatalog pesticides={[neem]} onAdd={onAdd} onEdit={onEdit} onClose={onClose} />
    ));

    const table = screen.getByRole("table", { name: "Pesticides" });
    expect(within(table).queryByRole("textbox")).not.toBeInTheDocument();
    expect(within(table).getByRole("columnheader", { name: "Type" })).toBeInTheDocument();
    expect(within(table).getByText("Insecticide")).toBeInTheDocument();
    expect(within(table).getByText(/Dilute before use/)).toHaveTextContent(
      "Dilute before use. Apply weekly.",
    );
    fireEvent.click(screen.getByRole("button", { name: "Edit Neem oil" }));
    expect(onEdit).toHaveBeenCalledWith(neem);
    fireEvent.click(screen.getByRole("button", { name: "Add pesticide" }));
    expect(onAdd).toHaveBeenCalledOnce();
    fireEvent.click(screen.getByRole("button", { name: "Collapse pesticide catalog" }));
    expect(onClose).toHaveBeenCalledOnce();
  });
});

describe("PesticideCatalogEditor", () => {
  it("should add a pesticide through constrained and multiline fields", async () => {
    const onAdd = vi
      .fn()
      .mockResolvedValueOnce({ kind: "addFailed", reason: new Error("private") })
      .mockResolvedValueOnce({ kind: "added", entry: neem });
    const onClose = vi.fn();
    render(() => (
      <PesticideCatalogEditor
        pesticide={undefined}
        onAdd={onAdd}
        onEdit={() => Promise.resolve({ kind: "edited", entry: neem })}
        onClose={onClose}
      />
    ));

    const form = screen.getByRole("form", { name: "Add pesticide" });
    fireEvent.submit(form);
    expect(screen.getByRole("alert")).toHaveTextContent("Enter a pesticide name.");
    expect(screen.getByRole("combobox", { name: "Type" })).toHaveDisplayValue("Fungicide");
    expect(
      within(screen.getByRole("combobox", { name: "Type" })).getAllByRole("option"),
    ).toHaveLength(3);

    fireEvent.input(screen.getByRole("textbox", { name: "Name" }), {
      target: { value: " Soap " },
    });
    fireEvent.change(screen.getByRole("combobox", { name: "Type" }), {
      target: { value: "insecticide" },
    });
    fireEvent.input(screen.getByRole("textbox", { name: "Info" }), {
      target: { value: " Dilute first.\nApply weekly. " },
    });
    fireEvent.submit(form);
    expect(await screen.findByRole("alert")).toHaveTextContent("The pesticide could not be saved.");
    fireEvent.submit(form);
    await waitFor(() => {
      expect(onClose).toHaveBeenCalledOnce();
    });
    expect(onAdd).toHaveBeenLastCalledWith({
      name: nomenclatureName("Soap"),
      pesticideType: "insecticide",
      maybeInfo: nomenclatureInfo("Dilute first.\nApply weekly."),
    });
  });

  it("should edit a pesticide and distinguish missing entries from save failures", async () => {
    const onEdit = vi
      .fn()
      .mockResolvedValueOnce({ kind: "recordMissing" })
      .mockResolvedValueOnce({ kind: "editFailed", reason: new Error("private") })
      .mockResolvedValueOnce({ kind: "edited", entry: neem });
    const onClose = vi.fn();
    render(() => (
      <PesticideCatalogEditor
        pesticide={neem}
        onAdd={() => Promise.resolve({ kind: "added", entry: neem })}
        onEdit={onEdit}
        onClose={onClose}
      />
    ));

    const form = screen.getByRole("form", { name: "Edit Neem oil" });
    const name = screen.getByRole("textbox", { name: "Name" });
    fireEvent.input(name, { target: { value: " " } });
    fireEvent.submit(form);
    expect(screen.getByRole("alert")).toHaveTextContent("Enter a pesticide name.");

    fireEvent.input(name, { target: { value: " Neem concentrate " } });
    fireEvent.change(screen.getByRole("combobox", { name: "Type" }), {
      target: { value: "treatment" },
    });
    fireEvent.submit(form);
    expect(await screen.findByRole("alert")).toHaveTextContent("This pesticide no longer exists.");
    fireEvent.submit(form);
    await waitFor(() =>
      expect(screen.getByRole("alert")).toHaveTextContent("The pesticide could not be saved."),
    );
    fireEvent.input(screen.getByRole("textbox", { name: "Info" }), {
      target: { value: " Use weekly. " },
    });
    fireEvent.submit(form);
    await waitFor(() => {
      expect(onClose).toHaveBeenCalledOnce();
    });
    expect(onEdit).toHaveBeenLastCalledWith(neemId, {
      name: nomenclatureName("Neem concentrate"),
      pesticideType: "treatment",
      maybeInfo: nomenclatureInfo("Use weekly."),
    });
  });

  it("should collapse without saving", () => {
    const onClose = vi.fn();
    render(() => (
      <PesticideCatalogEditor
        pesticide={neem}
        onAdd={() => Promise.resolve({ kind: "added", entry: neem })}
        onEdit={() => Promise.resolve({ kind: "edited", entry: neem })}
        onClose={onClose}
      />
    ));

    fireEvent.click(screen.getByRole("button", { name: "Collapse pesticide editor" }));
    expect(onClose).toHaveBeenCalledOnce();
  });
});
