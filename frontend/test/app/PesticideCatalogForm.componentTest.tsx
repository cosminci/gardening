import { fireEvent, render, screen, waitFor, within } from "@solidjs/testing-library";
import { describe, expect, it, vi } from "vitest";
import { PesticideCatalogForm } from "../../src/app/PesticideCatalogForm";
import {
  nomenclatureInfo,
  nomenclatureName,
  pesticideId,
  pesticideType,
} from "../../src/domain/Journal";

const neemId = pesticideId("00000000-0000-4000-8001-000000000003");
const neem = {
  id: neemId,
  data: {
    name: nomenclatureName("Neem oil"),
    pesticideType: pesticideType("Insecticide"),
    maybeInfo: null,
  },
};

describe("PesticideCatalogForm", () => {
  it("should validate additions and expose compact save actions", async () => {
    const onAdd = vi
      .fn()
      .mockResolvedValueOnce({ kind: "addFailed", reason: new Error("private") })
      .mockResolvedValueOnce({ kind: "added", entry: neem });
    render(() => (
      <PesticideCatalogForm
        pesticides={[neem]}
        onAdd={onAdd}
        onEdit={() => Promise.resolve({ kind: "edited", entry: neem })}
        onClose={() => {
          return undefined;
        }}
      />
    ));

    const editForm = screen.getByRole("form", { name: "Edit Neem oil" });
    expect(within(editForm).getByRole("button", { name: "Save Neem oil" })).toHaveTextContent(
      "Save",
    );
    const addForm = screen.getByRole("form", { name: "Add pesticide" });
    fireEvent.submit(addForm);
    expect(screen.getByRole("alert")).toHaveTextContent("Enter a pesticide name and type.");

    fireEvent.input(screen.getByRole("textbox", { name: "New pesticide name" }), {
      target: { value: " Soap " },
    });
    fireEvent.input(screen.getByRole("textbox", { name: "New pesticide type" }), {
      target: { value: " Contact " },
    });
    fireEvent.input(screen.getByRole("textbox", { name: "New pesticide info" }), {
      target: { value: " Dilute first " },
    });
    fireEvent.submit(addForm);
    expect(await screen.findByRole("alert")).toHaveTextContent("The pesticide could not be saved.");
    fireEvent.submit(addForm);
    await waitFor(() => {
      expect(onAdd).toHaveBeenCalledTimes(2);
    });
    expect(onAdd).toHaveBeenLastCalledWith({
      name: nomenclatureName("Soap"),
      pesticideType: pesticideType("Contact"),
      maybeInfo: nomenclatureInfo("Dilute first"),
    });
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "New pesticide name" })).toHaveValue("");
  });

  it("should validate edits and distinguish missing entries from save failures", async () => {
    const onEdit = vi
      .fn()
      .mockResolvedValueOnce({ kind: "recordMissing" })
      .mockResolvedValueOnce({ kind: "editFailed", reason: new Error("private") })
      .mockResolvedValueOnce({ kind: "edited", entry: neem });
    let closed = false;
    render(() => (
      <PesticideCatalogForm
        pesticides={[neem]}
        onAdd={() => Promise.resolve({ kind: "added", entry: neem })}
        onEdit={onEdit}
        onClose={() => {
          closed = true;
        }}
      />
    ));

    const name = screen.getByRole("textbox", { name: "Name for Neem oil" });
    const type = screen.getByRole("textbox", { name: "Type for Neem oil" });
    const info = screen.getByRole("textbox", { name: "Info for Neem oil" });
    const form = screen.getByRole("form", { name: "Edit Neem oil" });
    fireEvent.input(type, { target: { value: " " } });
    fireEvent.submit(form);
    expect(screen.getByRole("alert")).toHaveTextContent("Enter a pesticide name and type.");

    fireEvent.input(name, { target: { value: " Neem concentrate " } });
    fireEvent.input(type, { target: { value: " Botanical " } });
    fireEvent.submit(form);
    expect(await screen.findByRole("alert")).toHaveTextContent("This pesticide no longer exists.");
    fireEvent.submit(form);
    await waitFor(() =>
      expect(screen.getByRole("alert")).toHaveTextContent("The pesticide could not be saved."),
    );
    fireEvent.input(info, { target: { value: " Use weekly " } });
    fireEvent.submit(form);
    await waitFor(() => expect(screen.queryByRole("alert")).not.toBeInTheDocument());
    expect(onEdit).toHaveBeenLastCalledWith(neemId, {
      name: nomenclatureName("Neem concentrate"),
      pesticideType: pesticideType("Botanical"),
      maybeInfo: nomenclatureInfo("Use weekly"),
    });

    fireEvent.click(screen.getByRole("button", { name: "Close pesticide management" }));
    expect(closed).toBe(true);
  });
});
