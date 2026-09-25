import { fireEvent, render, screen, waitFor, within } from "@solidjs/testing-library";
import { createSignal } from "solid-js";
import { describe, expect, it, vi } from "vitest";
import { PlantSheet } from "../../src/app/PlantSheet";
import * as Journal from "../../src/domain/Journal";

const perlite: Journal.SubstrateComponent = {
  id: Journal.substrateComponentId("00000000-0000-4000-8000-000000000003"),
  data: { name: Journal.substrateComponentName("Perlite"), maybeInfo: null },
};

describe("PlantSheet", () => {
  it("should validate details and substrate before submitting a new plant", () => {
    const onSubmit = vi
      .fn<(_details: Journal.NewPlantDetails) => Promise<void>>()
      .mockResolvedValue(undefined);
    const { unmount } = render(() => (
      <PlantSheet
        target={{ kind: "add" }}
        components={[perlite]}
        saveError={undefined}
        completed={false}
        onSubmit={onSubmit}
        onAddComponent={() => Promise.resolve({ kind: "addFailed", reason: new Error("offline") })}
        onEditComponent={() =>
          Promise.resolve({ kind: "editFailed", reason: new Error("offline") })
        }
        substrateMixes={[]}
        onAddSubstrateMix={() =>
          Promise.resolve({ kind: "addFailed" as const, reason: new Error("offline") })
        }
        onRequestDeleteSubstrateMix={() => undefined}
        onCancel={() => undefined}
      />
    ));
    const dialog = screen.getByRole("dialog", { name: "Plant editor" });
    expect(dialog).toHaveFocus();
    fireEvent.click(within(dialog).getByRole("button", { name: "Save plant" }));
    expect(within(dialog).getByRole("alert")).toHaveTextContent("Enter a species.");
    fireEvent.input(within(dialog).getByRole("textbox", { name: "Species" }), {
      target: { value: " Aloe vera " },
    });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save plant" }));
    expect(within(dialog).getByRole("alert")).toHaveTextContent("Enter a location.");
    fireEvent.input(within(dialog).getByRole("textbox", { name: "Location" }), {
      target: { value: " Office " },
    });
    fireEvent.input(within(dialog).getByRole("textbox", { name: "Nickname (optional)" }), {
      target: { value: " Spike " },
    });
    fireEvent.input(within(dialog).getByRole("spinbutton", { name: "Component 1 share" }), {
      target: { value: "101" },
    });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save plant" }));
    expect(within(dialog).getByRole("alert")).toHaveTextContent(
      "Each substrate share must be a whole number from 1 to 100%.",
    );
    expect(onSubmit).not.toHaveBeenCalled();
    fireEvent.input(within(dialog).getByRole("spinbutton", { name: "Component 1 share" }), {
      target: { value: "100" },
    });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save plant" }));

    const expectedDetails: Journal.NewPlantDetails = {
      species: Journal.species("Aloe vera"),
      maybeNickname: Journal.nickname("Spike"),
      location: Journal.location("Office"),
      substrate: Journal.substrate([{ component: perlite.id, share: Journal.percentage(100) }]),
    };
    expect(onSubmit).toHaveBeenCalledWith(expectedDetails);
    unmount();
  });

  it("should let a plant with no catalog entries define its first substrate component", async () => {
    const [components, setComponents] = createSignal<readonly Journal.SubstrateComponent[]>([]);
    const onAddComponent = vi.fn<
      (
        _data: Journal.SubstrateComponentData,
      ) => Promise<Journal.CatalogAddResult<Journal.SubstrateComponent>>
    >(() => {
      setComponents([perlite]);
      return Promise.resolve({ kind: "added", entry: perlite });
    });
    render(() => (
      <PlantSheet
        target={{ kind: "add" }}
        components={components()}
        saveError={undefined}
        completed={false}
        onSubmit={() => Promise.resolve()}
        onAddComponent={onAddComponent}
        onEditComponent={() =>
          Promise.resolve({ kind: "editFailed", reason: new Error("offline") })
        }
        substrateMixes={[]}
        onAddSubstrateMix={() =>
          Promise.resolve({ kind: "addFailed" as const, reason: new Error("offline") })
        }
        onRequestDeleteSubstrateMix={() => undefined}
        onCancel={() => undefined}
      />
    ));
    fireEvent.input(screen.getByRole("textbox", { name: "Species" }), {
      target: { value: "Aloe" },
    });
    fireEvent.input(screen.getByRole("textbox", { name: "Location" }), {
      target: { value: "Office" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Save plant" }));
    expect(screen.getByRole("alert")).toHaveTextContent("Add at least one substrate component.");
    fireEvent.click(screen.getByRole("button", { name: "Define new component" }));
    expect(screen.getByRole("dialog", { name: "Substrate component editor" })).toHaveClass(
      "sheet--entering",
    );
    fireEvent.keyDown(window, { key: "Escape" });
    expect(screen.getByRole("dialog", { name: "Substrate component editor" })).toHaveClass(
      "sheet--closing",
    );
    await vi.waitFor(() => {
      expect(screen.queryByRole("dialog", { name: "Substrate component editor" })).toBeNull();
    });
    fireEvent.click(screen.getByRole("button", { name: "Define new component" }));
    const reopenedEditor = screen.getByRole("dialog", { name: "Substrate component editor" });
    fireEvent.keyDown(window, { key: "Tab" });
    expect(
      within(reopenedEditor).getByRole("heading", { name: "Add substrate component" }),
    ).toBeInTheDocument();
    fireEvent.input(within(reopenedEditor).getByRole("textbox", { name: "Name" }), {
      target: { value: "Perlite" },
    });
    fireEvent.click(within(reopenedEditor).getByRole("button", { name: "Save" }));

    await vi.waitFor(() => {
      expect(onAddComponent).toHaveBeenCalledOnce();
    });
    expect(screen.getByRole("combobox", { name: "Component 1" })).toHaveValue(perlite.id);
  });

  it("should reject components removed from the catalog and let users edit the mix", async () => {
    const [components, setComponents] = createSignal<readonly Journal.SubstrateComponent[]>([
      perlite,
    ]);
    const onEditComponent = vi.fn(() =>
      Promise.resolve({ kind: "edited" as const, entry: perlite }),
    );
    render(() => (
      <PlantSheet
        target={{ kind: "add" }}
        components={components()}
        saveError={undefined}
        completed={false}
        onSubmit={() => Promise.resolve()}
        onAddComponent={() => Promise.resolve({ kind: "addFailed", reason: new Error("offline") })}
        onEditComponent={onEditComponent}
        substrateMixes={[]}
        onAddSubstrateMix={() =>
          Promise.resolve({ kind: "addFailed" as const, reason: new Error("offline") })
        }
        onRequestDeleteSubstrateMix={() => undefined}
        onCancel={() => undefined}
      />
    ));
    fireEvent.click(screen.getByRole("button", { name: "Edit Perlite" }));
    const editor = screen.getByRole("dialog", { name: "Substrate component editor" });
    fireEvent.click(within(editor).getByRole("button", { name: "Save" }));
    await vi.waitFor(() => {
      expect(onEditComponent).toHaveBeenCalledOnce();
      expect(screen.queryByRole("dialog", { name: "Substrate component editor" })).toBeNull();
    });

    setComponents([]);
    fireEvent.input(screen.getByRole("textbox", { name: "Species" }), {
      target: { value: "Aloe" },
    });
    fireEvent.input(screen.getByRole("textbox", { name: "Location" }), {
      target: { value: "Office" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Save plant" }));

    expect(screen.getByRole("alert")).toHaveTextContent("Choose known substrate components.");
  });

  it("should keep the existing mix when defining an extra component for a stocked catalog", async () => {
    const pumice: Journal.SubstrateComponent = {
      id: Journal.substrateComponentId("00000000-0000-4000-8000-000000000005"),
      data: { name: Journal.substrateComponentName("Pumice"), maybeInfo: null },
    };
    const [components, setComponents] = createSignal<readonly Journal.SubstrateComponent[]>([
      perlite,
    ]);
    const onAddComponent = vi.fn<
      (
        _data: Journal.SubstrateComponentData,
      ) => Promise<Journal.CatalogAddResult<Journal.SubstrateComponent>>
    >(() => {
      setComponents([perlite, pumice]);
      return Promise.resolve({ kind: "added", entry: pumice });
    });
    render(() => (
      <PlantSheet
        target={{ kind: "add" }}
        components={components()}
        saveError={undefined}
        completed={false}
        onSubmit={() => Promise.resolve()}
        onAddComponent={onAddComponent}
        onEditComponent={() =>
          Promise.resolve({ kind: "editFailed", reason: new Error("offline") })
        }
        substrateMixes={[]}
        onAddSubstrateMix={() =>
          Promise.resolve({ kind: "addFailed" as const, reason: new Error("offline") })
        }
        onRequestDeleteSubstrateMix={() => undefined}
        onCancel={() => undefined}
      />
    ));
    expect(screen.getByRole("combobox", { name: "Component 1" })).toHaveValue(perlite.id);
    fireEvent.click(screen.getByRole("button", { name: "Define new component" }));
    const editor = screen.getByRole("dialog", { name: "Substrate component editor" });
    fireEvent.input(within(editor).getByRole("textbox", { name: "Name" }), {
      target: { value: "Pumice" },
    });
    fireEvent.click(within(editor).getByRole("button", { name: "Save" }));

    await vi.waitFor(() => {
      expect(onAddComponent).toHaveBeenCalledOnce();
      expect(screen.queryByRole("dialog", { name: "Substrate component editor" })).toBeNull();
    });
    expect(screen.getByRole("combobox", { name: "Component 1" })).toHaveValue(perlite.id);
    expect(screen.queryByRole("combobox", { name: "Component 2" })).toBeNull();
  });

  it("should collapse the nested editor before the plant sheet and restore focus", async () => {
    const [open, setOpen] = createSignal(false);
    render(() => (
      <>
        <header class="masthead">
          <button id="add-plant" onClick={() => setOpen(true)}>
            Add plant
          </button>
        </header>
        {open() && (
          <PlantSheet
            target={{ kind: "add" }}
            components={[perlite]}
            saveError={undefined}
            completed={false}
            onSubmit={() => Promise.resolve()}
            onAddComponent={() =>
              Promise.resolve({ kind: "addFailed", reason: new Error("offline") })
            }
            onEditComponent={() =>
              Promise.resolve({ kind: "editFailed", reason: new Error("offline") })
            }
            substrateMixes={[]}
            onAddSubstrateMix={() =>
              Promise.resolve({ kind: "addFailed" as const, reason: new Error("offline") })
            }
            onRequestDeleteSubstrateMix={() => undefined}
            onCancel={() => {
              setOpen(false);
            }}
          />
        )}
      </>
    ));
    fireEvent.click(screen.getByRole("button", { name: "Add plant" }));
    const dialog = screen.getByRole("dialog", { name: "Plant editor" });
    fireEvent.keyDown(window, { key: "Tab", shiftKey: true });
    expect(within(dialog).getByRole("button", { name: "Save plant" })).toHaveFocus();
    fireEvent.keyDown(window, { key: "Tab" });
    expect(within(dialog).getByRole("button", { name: "Collapse plant editor" })).toHaveFocus();
    fireEvent.keyDown(window, { key: "Tab" });
    expect(within(dialog).getByRole("button", { name: "Collapse plant editor" })).toHaveFocus();
    fireEvent.click(within(dialog).getByRole("button", { name: "Define new component" }));
    const editor = screen.getByRole("dialog", { name: "Substrate component editor" });
    expect(dialog.parentElement).toHaveClass("sheet-layer--editing");
    fireEvent.click(within(dialog).getByRole("button", { name: "Collapse plant editor" }));
    fireEvent.click(within(dialog).getByRole("button", { name: "Collapse plant editor" }));
    expect(editor).toHaveClass("sheet--closing");
    expect(dialog.parentElement).not.toHaveClass("sheet-layer--editing");
    await vi.waitFor(() => {
      expect(screen.queryByRole("dialog", { name: "Substrate component editor" })).toBeNull();
      expect(dialog).toHaveClass("sheet--closing");
    });
    await vi.waitFor(() => {
      expect(screen.queryByRole("dialog", { name: "Plant editor" })).toBeNull();
    });
    expect(screen.getByRole("button", { name: "Add plant" })).toHaveFocus();
    fireEvent.click(screen.getByRole("button", { name: "Add plant" }));
    fireEvent.keyDown(window, { key: "Escape" });
    expect(screen.getByRole("dialog", { name: "Plant editor" })).toHaveClass("sheet--closing");
    await vi.waitFor(() => {
      expect(screen.queryByRole("dialog", { name: "Plant editor" })).toBeNull();
    });
    expect(screen.getByRole("button", { name: "Add plant" })).toHaveFocus();
  });

  it("should prefill and submit revised details for an active plant", () => {
    const plant: Journal.Plant = {
      id: Journal.plantId("p1"),
      details: {
        species: Journal.species("Ficus lyrata"),
        maybeNickname: Journal.nickname("Fern"),
        location: Journal.location("Balcony"),
        substrate: Journal.substrate([{ component: perlite.id, share: Journal.percentage(100) }]),
        status: "active",
      },
    };
    const onSubmit = vi
      .fn<(_details: Journal.NewPlantDetails) => Promise<void>>()
      .mockResolvedValue(undefined);
    render(() => (
      <PlantSheet
        target={{ kind: "edit", plant }}
        components={[perlite]}
        saveError={undefined}
        completed={false}
        onSubmit={onSubmit}
        onAddComponent={() => Promise.resolve({ kind: "addFailed", reason: new Error("offline") })}
        onEditComponent={() =>
          Promise.resolve({ kind: "editFailed", reason: new Error("offline") })
        }
        substrateMixes={[]}
        onAddSubstrateMix={() =>
          Promise.resolve({ kind: "addFailed" as const, reason: new Error("offline") })
        }
        onRequestDeleteSubstrateMix={() => undefined}
        onCancel={() => undefined}
      />
    ));

    const dialog = screen.getByRole("dialog", { name: "Plant editor" });
    expect(within(dialog).getByRole("heading", { name: "Edit plant" })).toBeInTheDocument();
    expect(within(dialog).getByRole("textbox", { name: "Species" })).toHaveValue("Ficus lyrata");
    expect(within(dialog).getByRole("textbox", { name: "Nickname (optional)" })).toHaveValue(
      "Fern",
    );
    expect(within(dialog).getByRole("textbox", { name: "Location" })).toHaveValue("Balcony");
    expect(within(dialog).getByRole("spinbutton", { name: "Component 1 share" })).toHaveValue(100);
    fireEvent.input(within(dialog).getByRole("textbox", { name: "Location" }), {
      target: { value: "Living room" },
    });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));

    const expectedDetails: Journal.NewPlantDetails = {
      species: Journal.species("Ficus lyrata"),
      maybeNickname: Journal.nickname("Fern"),
      location: Journal.location("Living room"),
      substrate: Journal.substrate([{ component: perlite.id, share: Journal.percentage(100) }]),
    };
    expect(onSubmit).toHaveBeenCalledWith(expectedDetails);
  });

  it("should return focus to the plant's edit control after cancelling an edit", async () => {
    const plant: Journal.Plant = {
      id: Journal.plantId("p1"),
      details: {
        species: Journal.species("Ficus lyrata"),
        maybeNickname: null,
        location: Journal.location("Balcony"),
        substrate: Journal.substrate([{ component: perlite.id, share: Journal.percentage(100) }]),
        status: "active",
      },
    };
    const [open, setOpen] = createSignal(true);
    render(() => (
      <>
        <button id="edit-plant-p1">Edit Fern</button>
        {open() && (
          <PlantSheet
            target={{ kind: "edit", plant }}
            components={[perlite]}
            saveError={undefined}
            completed={false}
            onSubmit={() => Promise.resolve()}
            onAddComponent={() =>
              Promise.resolve({ kind: "addFailed", reason: new Error("offline") })
            }
            onEditComponent={() =>
              Promise.resolve({ kind: "editFailed", reason: new Error("offline") })
            }
            substrateMixes={[]}
            onAddSubstrateMix={() =>
              Promise.resolve({ kind: "addFailed" as const, reason: new Error("offline") })
            }
            onRequestDeleteSubstrateMix={() => undefined}
            onCancel={() => {
              setOpen(false);
            }}
          />
        )}
      </>
    ));

    fireEvent.keyDown(window, { key: "Escape" });
    await vi.waitFor(() => {
      expect(screen.queryByRole("dialog", { name: "Plant editor" })).toBeNull();
    });
    expect(screen.getByRole("button", { name: "Edit Fern" })).toHaveFocus();
  });

  it("should save the current substrate as a mix and load a saved mix back", async () => {
    const pineBark: Journal.SubstrateComponent = {
      id: Journal.substrateComponentId("00000000-0000-4000-8000-000000000004"),
      data: { name: Journal.substrateComponentName("Pine bark"), maybeInfo: null },
    };
    const savedMix: Journal.SubstrateMix = {
      id: Journal.substrateMixId("00000000-0000-4000-8000-000000000012"),
      name: Journal.substrateMixName("Perlite mix"),
      maybeNotes: null,
      substrate: Journal.substrate([{ component: perlite.id, share: Journal.percentage(100) }]),
    };
    const existingMix: Journal.SubstrateMix = {
      id: Journal.substrateMixId("00000000-0000-4000-8000-000000000011"),
      name: Journal.substrateMixName("Bark mix"),
      maybeNotes: null,
      substrate: Journal.substrate([{ component: pineBark.id, share: Journal.percentage(100) }]),
    };
    const onAddSubstrateMix = vi.fn().mockResolvedValue({ kind: "added", entry: savedMix });
    const onRequestDeleteSubstrateMix = vi.fn();
    render(() => (
      <PlantSheet
        target={{ kind: "add" }}
        components={[perlite, pineBark]}
        substrateMixes={[existingMix]}
        saveError={undefined}
        completed={false}
        onSubmit={() => Promise.resolve()}
        onAddComponent={() => Promise.resolve({ kind: "addFailed", reason: new Error("offline") })}
        onEditComponent={() =>
          Promise.resolve({ kind: "editFailed", reason: new Error("offline") })
        }
        onAddSubstrateMix={onAddSubstrateMix}
        onRequestDeleteSubstrateMix={onRequestDeleteSubstrateMix}
        onCancel={() => undefined}
      />
    ));

    fireEvent.click(screen.getByRole("button", { name: "Save mix" }));
    const saveSheet = screen.getByRole("dialog", { name: "Save substrate mix" });
    fireEvent.input(within(saveSheet).getByRole("textbox", { name: "Name" }), {
      target: { value: "Perlite mix" },
    });
    fireEvent.click(within(saveSheet).getByRole("button", { name: "Save" }));
    await waitFor(() => {
      expect(screen.queryByRole("dialog", { name: "Save substrate mix" })).toBeNull();
    });
    expect(onAddSubstrateMix).toHaveBeenCalledWith(
      Journal.substrateMixName("Perlite mix"),
      null,
      Journal.substrate([{ component: perlite.id, share: Journal.percentage(100) }]),
    );

    fireEvent.click(screen.getByRole("button", { name: "Load saved mix" }));
    const loadSheet = screen.getByRole("dialog", { name: "Load substrate mix" });
    expect(within(loadSheet).getByText("Bark mix")).toBeInTheDocument();
    fireEvent.click(within(loadSheet).getByRole("button", { name: "Load" }));
    await waitFor(() => {
      expect(screen.queryByRole("dialog", { name: "Load substrate mix" })).toBeNull();
    });
    expect(screen.getByRole("combobox", { name: "Component 1" })).toHaveValue(pineBark.id);

    fireEvent.click(screen.getByRole("button", { name: "Load saved mix" }));
    fireEvent.click(screen.getByRole("button", { name: "Delete Bark mix" }));
    expect(onRequestDeleteSubstrateMix).toHaveBeenCalledWith(existingMix);

    fireEvent.click(screen.getByRole("button", { name: "Collapse load mix editor" }));
    await waitFor(() => {
      expect(screen.queryByRole("dialog", { name: "Load substrate mix" })).toBeNull();
    });
  });
});
