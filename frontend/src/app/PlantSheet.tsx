import { Show, createSignal, onCleanup, onMount, untrack } from "solid-js";
import type { Component } from "solid-js";
import * as Journal from "../domain/Journal";
import { useBackgroundBarrier } from "./BackgroundBarrier";
import { LoadSubstrateMixSheet } from "./LoadSubstrateMixSheet";
import * as Controls from "./OperationControlIds";
import { SaveSubstrateMixSheet } from "./SaveSubstrateMixSheet";
import { SubstrateComponentArchiveConfirmation } from "./SubstrateComponentArchiveConfirmation";
import { SubstrateComponentEditor } from "./SubstrateComponentEditor";
import { SubstrateFields, validateSubstrate } from "./SubstrateFields";
import type { SubstratePartInput } from "./SubstrateFields";
import "./form-fields.css";
import "./operation-form.css";
import "./sheet.css";

export type PlantTarget =
  { readonly kind: "add" } | { readonly kind: "edit"; readonly plant: Journal.Plant };

export const editPlantControlId = (plantId: Journal.PlantId) => `edit-plant-${plantId}`;

interface PlantSheetProps {
  readonly target: PlantTarget;
  readonly components: readonly Journal.SubstrateComponent[];
  readonly substrateMixes: readonly Journal.SubstrateMix[];
  readonly saveError: string | undefined;
  readonly completed: boolean;
  readonly onSubmit: (details: Journal.NewPlantDetails) => Promise<void>;
  readonly onAddComponent: (
    data: Journal.SubstrateComponentData,
  ) => Promise<Journal.CatalogAddResult<Journal.SubstrateComponent>>;
  readonly onEditComponent: (
    id: Journal.SubstrateComponentId,
    data: Journal.SubstrateComponentData,
  ) => Promise<Journal.SubstrateComponentEditResult>;
  readonly onArchiveComponent: (
    id: Journal.SubstrateComponentId,
  ) => Promise<Journal.SubstrateComponentArchiveResult>;
  readonly onAddSubstrateMix: (
    name: Journal.SubstrateMixName,
    maybeNotes: Journal.SubstrateMixNotes | null,
    substrate: Journal.Substrate,
  ) => Promise<Journal.AddSubstrateMixResult>;
  readonly onRequestDeleteSubstrateMix: (mix: Journal.SubstrateMix) => void;
  readonly onCancel: () => void;
  readonly deleteMixConfirming?: boolean | undefined;
}

type SecondarySheet =
  | {
      readonly kind: "component";
      readonly component: Journal.SubstrateComponent | undefined;
      readonly returnFocusId: string;
    }
  | { readonly kind: "saveMix"; readonly returnFocusId: string }
  | { readonly kind: "loadMix"; readonly returnFocusId: string };

const secondarySheetLabels: Record<SecondarySheet["kind"], string> = {
  component: "Substrate component editor",
  saveMix: "Save substrate mix",
  loadMix: "Load substrate mix",
};

export const PlantSheet: Component<PlantSheetProps> = (props) => {
  let dialog!: HTMLElement;
  let collapseButton!: HTMLButtonElement;
  let saveButton!: HTMLButtonElement;
  const isEdit = untrack(() => props.target.kind === "edit");
  const initialDetails = untrack(() =>
    props.target.kind === "edit" ? props.target.plant.details : undefined,
  );
  const firstComponent = untrack(() => props.components[0]);
  const [species, setSpecies] = createSignal(initialDetails?.species ?? "");
  const [nickname, setNickname] = createSignal(initialDetails?.maybeNickname ?? "");
  const [location, setLocation] = createSignal(initialDetails?.location ?? "");
  const [parts, setParts] = createSignal<SubstratePartInput[]>(
    initialDetails !== undefined
      ? initialDetails.substrate.map((part) => ({ component: part.component, share: part.share }))
      : firstComponent === undefined
        ? []
        : [{ component: firstComponent.id, share: 100 }],
  );
  const [validationError, setValidationError] = createSignal<string>();
  const [submitting, setSubmitting] = createSignal(false);
  const [editor, setEditor] = createSignal<SecondarySheet>();
  const [closingSheet, setClosingSheet] = createSignal<"editor" | "plant">();
  const [archiveTarget, setArchiveTarget] = createSignal<Journal.SubstrateComponent>();
  const [archiveCompleted, setArchiveCompleted] = createSignal(false);
  useBackgroundBarrier();

  const waitForSheetTransition = () =>
    new Promise<void>((resolve) => {
      window.setTimeout(resolve, 180);
    });

  const closeSheets = async (target: "editor" | "plant") => {
    if (closingSheet() !== undefined) return;
    const currentEditor = editor();
    if (currentEditor !== undefined) {
      setClosingSheet("editor");
      await waitForSheetTransition();
      setEditor(undefined);
      if (target === "editor") {
        setClosingSheet(undefined);
        queueMicrotask(() => document.getElementById(currentEditor.returnFocusId)?.focus());
        return;
      }
    }
    setClosingSheet("plant");
    await waitForSheetTransition();
    props.onCancel();
  };

  const onKeyDown = (event: KeyboardEvent) => {
    if (
      event.key === "Escape" &&
      !submitting() &&
      props.deleteMixConfirming !== true &&
      archiveTarget() === undefined
    ) {
      event.preventDefault();
      void closeSheets(editor() === undefined ? "plant" : "editor");
    }
    if (event.key !== "Tab" || editor() !== undefined) return;
    if (
      event.shiftKey &&
      (document.activeElement === collapseButton || document.activeElement === dialog)
    ) {
      event.preventDefault();
      saveButton.focus();
    } else if (!event.shiftKey && document.activeElement === saveButton) {
      event.preventDefault();
      collapseButton.focus();
    }
  };

  const submit = async (event: SubmitEvent) => {
    event.preventDefault();
    const name = species().trim();
    const place = location().trim();
    const error =
      name === ""
        ? "Enter a species."
        : place === ""
          ? "Enter a location."
          : (validateSubstrate(parts()) ??
            (parts().some(
              (part) => !props.components.some((component) => component.id === part.component),
            )
              ? "Choose known substrate components."
              : undefined));
    setValidationError(error);
    if (error !== undefined) return;
    const details: Journal.NewPlantDetails = {
      species: Journal.species(name),
      maybeNickname: nickname().trim() === "" ? null : Journal.nickname(nickname().trim()),
      location: Journal.location(place),
      substrate: Journal.substrate(
        parts().map((part) => ({
          component: part.component,
          share: Journal.percentage(part.share),
        })),
      ),
    };
    dialog.focus();
    setSubmitting(true);
    try {
      await props.onSubmit(details);
    } finally {
      setSubmitting(false);
    }
  };

  const addComponent = async (data: Journal.SubstrateComponentData) => {
    const result = await props.onAddComponent(data);
    if (result.kind === "added" && parts().length === 0)
      setParts([{ component: result.entry.id, share: 100 }]);
    return result;
  };

  const editComponent = (id: Journal.SubstrateComponentId, data: Journal.SubstrateComponentData) =>
    props.onEditComponent(id, data);

  const confirmArchiveComponent = async (
    component: Journal.SubstrateComponent,
  ): Promise<string | undefined> => {
    let result: Journal.SubstrateComponentArchiveResult;
    try {
      result = await props.onArchiveComponent(component.id);
    } catch {
      return "The substrate component could not be archived.";
    }
    if (result.kind === "componentMissing") return "This substrate component no longer exists.";
    if (result.kind === "alreadyArchived") return "This substrate component was already archived.";
    if (result.kind === "archiveFailed") return "The substrate component could not be archived.";
    setArchiveCompleted(true);
    setArchiveTarget(undefined);
    const currentEditor = editor();
    // The archive action is only reachable from within this component's own open editor, so the
    // editor is always still open here; the false side is unreachable at runtime.
    /* v8 ignore next */
    if (currentEditor !== undefined) void closeSheets("editor");
    return undefined;
  };

  const currentSubstrate = () =>
    Journal.substrate(
      parts().map((part) => ({ component: part.component, share: Journal.percentage(part.share) })),
    );

  onMount(() => {
    window.addEventListener("keydown", onKeyDown);
    dialog.focus();
  });

  onCleanup(() => {
    window.removeEventListener("keydown", onKeyDown);
    const returnFocusId =
      props.target.kind === "edit"
        ? editPlantControlId(props.target.plant.id)
        : props.completed
          ? "garden-toggle"
          : "add-plant";
    document.getElementById(returnFocusId)?.focus();
  });

  const editorContent = (current: SecondarySheet) => {
    switch (current.kind) {
      case "component":
        return (
          <SubstrateComponentEditor
            component={current.component}
            onAdd={addComponent}
            onEdit={editComponent}
            onArchive={
              current.component === undefined
                ? undefined
                : {
                    controlId: Controls.archiveSubstrateComponentControlId(current.component.id),
                    onClick: () => {
                      setArchiveCompleted(false);
                      setArchiveTarget(current.component);
                    },
                  }
            }
            onClose={() => void closeSheets("editor")}
          />
        );
      case "saveMix":
        return (
          <SaveSubstrateMixSheet
            onSave={(name, maybeNotes) =>
              props.onAddSubstrateMix(name, maybeNotes, currentSubstrate())
            }
            onClose={() => void closeSheets("editor")}
          />
        );
      case "loadMix":
        return (
          <LoadSubstrateMixSheet
            mixes={props.substrateMixes}
            components={props.components}
            onLoad={(mix) => {
              setParts(
                mix.substrate.map((part) => ({ component: part.component, share: part.share })),
              );
              void closeSheets("editor");
            }}
            onRequestDelete={props.onRequestDeleteSubstrateMix}
            onClose={() => void closeSheets("editor")}
          />
        );
    }
  };

  return (
    <div
      class="sheet-layer"
      classList={{ "sheet-layer--editing": editor() !== undefined && closingSheet() !== "editor" }}
    >
      <div class="sheet-layer__scrim" aria-hidden="true" />
      <aside
        ref={(element) => {
          dialog = element;
        }}
        class="sheet sheet--operation sheet--plant sheet--entering"
        classList={{ "sheet--closing": closingSheet() === "plant" }}
        role="dialog"
        aria-label="Plant editor"
        aria-modal={editor() === undefined ? "true" : undefined}
        tabIndex="-1"
        inert={closingSheet() === "plant"}
      >
        <form
          class="operation-form"
          aria-label={isEdit ? "Edit plant" : "Add plant"}
          noValidate
          onSubmit={(event) => void submit(event)}
        >
          <header class="operation-form__header">
            <h2>{isEdit ? "Edit plant" : "Add plant"}</h2>
            <button
              ref={(element) => {
                collapseButton = element;
              }}
              class="icon-action sheet-collapse"
              type="button"
              aria-label="Collapse plant editor"
              disabled={submitting()}
              onClick={() => {
                void closeSheets("plant");
              }}
            >
              <span class="sheet-collapse__icon" aria-hidden="true" />
            </button>
          </header>
          <div class="operation-form__body" inert={editor() !== undefined}>
            <label class="field">
              <span>Species</span>
              <input
                type="text"
                value={species()}
                aria-invalid={validationError() === "Enter a species."}
                onInput={(event) => setSpecies(event.currentTarget.value)}
              />
            </label>
            <label class="field">
              <span>Nickname (optional)</span>
              <input
                type="text"
                value={nickname()}
                onInput={(event) => setNickname(event.currentTarget.value)}
              />
            </label>
            <label class="field">
              <span>Location</span>
              <input
                type="text"
                value={location()}
                aria-invalid={validationError() === "Enter a location."}
                onInput={(event) => setLocation(event.currentTarget.value)}
              />
            </label>
            <SubstrateFields
              parts={parts()}
              components={props.components}
              onChange={setParts}
              onAddComponent={() =>
                setEditor({
                  kind: "component",
                  component: undefined,
                  returnFocusId: Controls.addSubstrateComponentControlId,
                })
              }
              onEditComponent={(component, returnFocusId) =>
                setEditor({ kind: "component", component, returnFocusId })
              }
              onSaveMix={() => {
                setEditor({ kind: "saveMix", returnFocusId: Controls.saveSubstrateMixControlId });
              }}
              onLoadMix={() => {
                setEditor({ kind: "loadMix", returnFocusId: Controls.loadSubstrateMixControlId });
              }}
            />
            <Show when={validationError()}>{(message) => <p role="alert">{message()}</p>}</Show>
            <Show when={props.saveError}>
              {(message) => (
                <p class="inline-alert" role="alert">
                  {message()}
                </p>
              )}
            </Show>
            <footer class="operation-form__actions">
              <button
                ref={(element) => {
                  saveButton = element;
                }}
                class="primary-action"
                type="submit"
                disabled={submitting()}
              >
                {submitting() ? "Saving…" : isEdit ? "Save changes" : "Save plant"}
              </button>
            </footer>
          </div>
        </form>
      </aside>
      <Show keyed when={editor()}>
        {(current) => (
          <aside
            class="sheet sheet--catalog-editor sheet--entering"
            classList={{ "sheet--closing": closingSheet() === "editor" }}
            inert={closingSheet() === "editor"}
            role="dialog"
            aria-modal="true"
            aria-label={secondarySheetLabels[current.kind]}
          >
            {editorContent(current)}
          </aside>
        )}
      </Show>
      <Show when={archiveTarget()} keyed>
        {(component) => (
          <SubstrateComponentArchiveConfirmation
            component={component}
            completed={archiveCompleted()}
            onConfirm={() => confirmArchiveComponent(component)}
            onCancel={() => {
              setArchiveTarget(undefined);
            }}
          />
        )}
      </Show>
    </div>
  );
};
