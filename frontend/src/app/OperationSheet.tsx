import { Show, createSignal, onCleanup, onMount } from "solid-js";
import type { Component } from "solid-js";
import type * as Journal from "../domain/Journal";
import { LoadSubstrateMixSheet } from "./LoadSubstrateMixSheet";
import * as Controls from "./OperationControlIds";
import { OperationForm } from "./OperationForm";
import type { DeleteAction } from "./OperationForm";
import { PesticideArchiveConfirmation } from "./PesticideArchiveConfirmation";
import { PesticideEditor } from "./PesticideEditor";
import { SaveSubstrateMixSheet } from "./SaveSubstrateMixSheet";
import { SubstrateComponentArchiveConfirmation } from "./SubstrateComponentArchiveConfirmation";
import { SubstrateComponentEditor } from "./SubstrateComponentEditor";
import "./sheet.css";

export type OperationTarget =
  | { readonly kind: "log"; readonly plantId: Journal.PlantId }
  | { readonly kind: "edit"; readonly operation: Journal.Operation };

export const operationControlId = (target: OperationTarget) =>
  target.kind === "log"
    ? Controls.logOperationControlId(target.plantId)
    : Controls.editOperationControlId(target.operation.id);

interface OperationSheetProps {
  readonly target: OperationTarget;
  readonly substrateComponents: readonly Journal.SubstrateComponent[];
  readonly substrateMixes: readonly Journal.SubstrateMix[];
  readonly pesticides: readonly Journal.Pesticide[];
  readonly saveError: string | undefined;
  readonly onSubmit: (details: Journal.OperationDetails, date: Journal.Instant) => Promise<void>;
  readonly onAddSubstrateComponent: (
    data: Journal.SubstrateComponentData,
  ) => Promise<Journal.CatalogAddResult<Journal.SubstrateComponent>>;
  readonly onEditSubstrateComponent: (
    id: Journal.SubstrateComponentId,
    data: Journal.SubstrateComponentData,
  ) => Promise<Journal.SubstrateComponentEditResult>;
  readonly onArchiveSubstrateComponent: (
    id: Journal.SubstrateComponentId,
  ) => Promise<Journal.SubstrateComponentArchiveResult>;
  readonly onAddSubstrateMix: (
    name: Journal.SubstrateMixName,
    maybeNotes: Journal.SubstrateMixNotes | null,
    substrate: Journal.Substrate,
  ) => Promise<Journal.AddSubstrateMixResult>;
  readonly onRequestDeleteSubstrateMix: (mix: Journal.SubstrateMix) => void;
  readonly onAddPesticide: (
    data: Journal.PesticideData,
  ) => Promise<Journal.CatalogAddResult<Journal.Pesticide>>;
  readonly onEditPesticide: (
    id: Journal.PesticideId,
    data: Journal.PesticideData,
  ) => Promise<Journal.PesticideEditResult>;
  readonly onArchivePesticide: (id: Journal.PesticideId) => Promise<Journal.PesticideArchiveResult>;
  readonly onCancel: () => void;
  readonly onDelete?: DeleteAction | undefined;
  readonly deleteConfirming?: boolean | undefined;
}

type SecondarySheet =
  | {
      readonly kind: "substrate";
      readonly component: Journal.SubstrateComponent | undefined;
      readonly returnFocusId: string;
    }
  | {
      readonly kind: "pesticide";
      readonly pesticide: Journal.Pesticide | undefined;
      readonly returnFocusId: string;
    }
  | {
      readonly kind: "saveMix";
      readonly substrate: Journal.Substrate;
      readonly returnFocusId: string;
    }
  | {
      readonly kind: "loadMix";
      readonly returnFocusId: string;
    };

type Sheet = "editor" | "operation";

const sheetTransitionMilliseconds = 180;

const secondarySheetLabels: Record<SecondarySheet["kind"], string> = {
  substrate: "Substrate component editor",
  pesticide: "Pesticide editor",
  saveMix: "Save substrate mix",
  loadMix: "Load substrate mix",
};

export const OperationSheet: Component<OperationSheetProps> = (props) => {
  let dialog!: HTMLElement;
  let loadSubstrateIntoForm: ((substrate: Journal.Substrate) => void) | undefined;
  const [editor, setEditor] = createSignal<SecondarySheet>();
  const [closingSheet, setClosingSheet] = createSignal<Sheet>();
  const [pesticideArchiveTarget, setPesticideArchiveTarget] = createSignal<Journal.Pesticide>();
  const [pesticideArchiveCompleted, setPesticideArchiveCompleted] = createSignal(false);
  const [substrateArchiveTarget, setSubstrateArchiveTarget] =
    createSignal<Journal.SubstrateComponent>();
  const [substrateArchiveCompleted, setSubstrateArchiveCompleted] = createSignal(false);
  const initial = () => (props.target.kind === "edit" ? props.target.operation.details : undefined);
  const returnFocusId = () => operationControlId(props.target);
  const background = [...document.querySelectorAll<HTMLElement>(".masthead, .journal")];

  const waitForSheetTransition = () =>
    new Promise<void>((resolve) => {
      window.setTimeout(resolve, sheetTransitionMilliseconds);
    });

  const restoreFocus = (controlId: string) => {
    queueMicrotask(() => {
      document.getElementById(controlId)?.focus();
    });
  };

  const closeSheets = async (target: Sheet, currentEditor = editor()) => {
    if (closingSheet() !== undefined) return;

    if (currentEditor !== undefined) {
      setClosingSheet("editor");
      await waitForSheetTransition();
      setEditor(undefined);
      if (target === "editor") {
        setClosingSheet(undefined);
        restoreFocus(currentEditor.returnFocusId);
        return;
      }
    }

    setClosingSheet("operation");
    await waitForSheetTransition();
    props.onCancel();
  };

  const closeEditor = (current: SecondarySheet) => {
    void closeSheets("editor", current);
  };

  const closeOperation = () => {
    void closeSheets("operation");
  };

  const closeOnEscape = (event: KeyboardEvent) => {
    if (
      event.key !== "Escape" ||
      props.deleteConfirming === true ||
      pesticideArchiveTarget() !== undefined ||
      substrateArchiveTarget() !== undefined
    )
      return;
    const currentEditor = editor();
    if (currentEditor !== undefined) closeEditor(currentEditor);
    else closeOperation();
  };

  const confirmArchivePesticide = async (
    pesticide: Journal.Pesticide,
  ): Promise<string | undefined> => {
    let result: Journal.PesticideArchiveResult;
    try {
      result = await props.onArchivePesticide(pesticide.id);
    } catch {
      return "The pesticide could not be archived.";
    }
    if (result.kind === "pesticideMissing") return "This pesticide no longer exists.";
    if (result.kind === "alreadyArchived") return "This pesticide was already archived.";
    if (result.kind === "archiveFailed") return "The pesticide could not be archived.";
    setPesticideArchiveCompleted(true);
    setPesticideArchiveTarget(undefined);
    const currentEditor = editor();
    // The archive action is only reachable from within this pesticide's own open editor, so the
    // editor is always still open here; the false side is unreachable at runtime.
    /* v8 ignore next */
    if (currentEditor !== undefined) closeEditor(currentEditor);
    return undefined;
  };

  const confirmArchiveSubstrateComponent = async (
    component: Journal.SubstrateComponent,
  ): Promise<string | undefined> => {
    let result: Journal.SubstrateComponentArchiveResult;
    try {
      result = await props.onArchiveSubstrateComponent(component.id);
    } catch {
      return "The substrate component could not be archived.";
    }
    if (result.kind === "componentMissing") return "This substrate component no longer exists.";
    if (result.kind === "alreadyArchived") return "This substrate component was already archived.";
    if (result.kind === "archiveFailed") return "The substrate component could not be archived.";
    setSubstrateArchiveCompleted(true);
    setSubstrateArchiveTarget(undefined);
    const currentEditor = editor();
    // The archive action is only reachable from within this component's own open editor, so the
    // editor is always still open here; the false side is unreachable at runtime.
    /* v8 ignore next */
    if (currentEditor !== undefined) closeEditor(currentEditor);
    return undefined;
  };

  const editorContent = (current: SecondarySheet) => {
    switch (current.kind) {
      case "substrate":
        return (
          <SubstrateComponentEditor
            component={current.component}
            onAdd={props.onAddSubstrateComponent}
            onEdit={props.onEditSubstrateComponent}
            onArchive={
              current.component === undefined
                ? undefined
                : {
                    controlId: Controls.archiveSubstrateComponentControlId(current.component.id),
                    onClick: () => {
                      setSubstrateArchiveCompleted(false);
                      setSubstrateArchiveTarget(current.component);
                    },
                  }
            }
            onClose={() => {
              closeEditor(current);
            }}
          />
        );
      case "pesticide":
        return (
          <PesticideEditor
            pesticide={current.pesticide}
            onAdd={props.onAddPesticide}
            onEdit={props.onEditPesticide}
            onArchive={
              current.pesticide === undefined
                ? undefined
                : {
                    controlId: Controls.archivePesticideControlId(current.pesticide.id),
                    onClick: () => {
                      setPesticideArchiveCompleted(false);
                      setPesticideArchiveTarget(current.pesticide);
                    },
                  }
            }
            onClose={() => {
              closeEditor(current);
            }}
          />
        );
      case "saveMix":
        return (
          <SaveSubstrateMixSheet
            onSave={(name, maybeNotes) =>
              props.onAddSubstrateMix(name, maybeNotes, current.substrate)
            }
            onClose={() => {
              closeEditor(current);
            }}
          />
        );
      case "loadMix":
        return (
          <LoadSubstrateMixSheet
            mixes={props.substrateMixes}
            components={props.substrateComponents}
            onLoad={(mix) => {
              loadSubstrateIntoForm?.(mix.substrate);
              closeEditor(current);
            }}
            onRequestDelete={props.onRequestDeleteSubstrateMix}
            onClose={() => {
              closeEditor(current);
            }}
          />
        );
    }
  };

  onMount(() => {
    background.forEach((element) => {
      element.inert = true;
    });
    window.addEventListener("keydown", closeOnEscape);
    dialog.focus();
  });

  onCleanup(() => {
    background.forEach((element) => {
      element.inert = false;
    });
    window.removeEventListener("keydown", closeOnEscape);
    document.getElementById(returnFocusId())?.focus();
  });

  return (
    <div
      class="sheet-layer"
      classList={{
        "sheet-layer--editing": editor() !== undefined && closingSheet() !== "editor",
      }}
    >
      <div class="sheet-layer__scrim" aria-hidden="true" />
      <aside
        ref={(element) => {
          dialog = element;
        }}
        class="sheet sheet--operation sheet--entering"
        classList={{ "sheet--closing": closingSheet() === "operation" }}
        inert={closingSheet() === "operation"}
        aria-label="Operation editor"
        aria-modal={editor() === undefined ? "true" : undefined}
        role="dialog"
        tabIndex="-1"
      >
        <OperationForm
          initial={initial()}
          substrateComponents={props.substrateComponents}
          pesticides={props.pesticides}
          onSubmit={props.onSubmit}
          inactive={editor() !== undefined}
          onAddSubstrateComponent={() => {
            setEditor({
              kind: "substrate",
              component: undefined,
              returnFocusId: Controls.addSubstrateComponentControlId,
            });
          }}
          onEditSubstrateComponent={(component, controlId) => {
            setEditor({ kind: "substrate", component, returnFocusId: controlId });
          }}
          onSaveSubstrateMix={(substrate) => {
            setEditor({
              kind: "saveMix",
              substrate,
              returnFocusId: Controls.saveSubstrateMixControlId,
            });
          }}
          onLoadSubstrateMix={() => {
            setEditor({ kind: "loadMix", returnFocusId: Controls.loadSubstrateMixControlId });
          }}
          onRegisterSubstrateLoader={(load) => {
            loadSubstrateIntoForm = load;
          }}
          onAddPesticide={() => {
            setEditor({
              kind: "pesticide",
              pesticide: undefined,
              returnFocusId: Controls.addPesticideControlId,
            });
          }}
          onEditPesticide={(pesticide) => {
            setEditor({
              kind: "pesticide",
              pesticide,
              returnFocusId: Controls.editPesticideControlId(pesticide.id),
            });
          }}
          onCancel={closeOperation}
          onDelete={props.onDelete}
        />
        <Show when={props.saveError}>
          {(message) => (
            <p class="inline-alert" role="alert">
              {message()}
            </p>
          )}
        </Show>
      </aside>
      <Show keyed when={editor()}>
        {(current) => (
          <aside
            class="sheet sheet--catalog-editor"
            classList={{ "sheet--closing": closingSheet() === "editor" }}
            inert={closingSheet() === "editor"}
            aria-label={secondarySheetLabels[current.kind]}
            aria-modal="true"
            role="dialog"
          >
            {editorContent(current)}
          </aside>
        )}
      </Show>
      <Show when={pesticideArchiveTarget()} keyed>
        {(pesticide) => (
          <PesticideArchiveConfirmation
            pesticide={pesticide}
            completed={pesticideArchiveCompleted()}
            onConfirm={() => confirmArchivePesticide(pesticide)}
            onCancel={() => {
              setPesticideArchiveTarget(undefined);
            }}
          />
        )}
      </Show>
      <Show when={substrateArchiveTarget()} keyed>
        {(component) => (
          <SubstrateComponentArchiveConfirmation
            component={component}
            completed={substrateArchiveCompleted()}
            onConfirm={() => confirmArchiveSubstrateComponent(component)}
            onCancel={() => {
              setSubstrateArchiveTarget(undefined);
            }}
          />
        )}
      </Show>
    </div>
  );
};
