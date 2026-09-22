import { Show, createSignal, onCleanup, onMount } from "solid-js";
import type { Component } from "solid-js";
import type * as Journal from "../domain/Journal";
import * as Controls from "./OperationControlIds";
import { OperationForm } from "./OperationForm";
import { PesticideEditor } from "./PesticideEditor";
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
  readonly pesticides: readonly Journal.Pesticide[];
  readonly saveError: string | undefined;
  readonly onSubmit: (details: Journal.OperationDetails) => Promise<void>;
  readonly onAddSubstrateComponent: (
    data: Journal.SubstrateComponentData,
  ) => Promise<Journal.CatalogAddResult<Journal.SubstrateComponent>>;
  readonly onEditSubstrateComponent: (
    id: Journal.SubstrateComponentId,
    data: Journal.SubstrateComponentData,
  ) => Promise<Journal.CatalogEditResult<Journal.SubstrateComponent>>;
  readonly onAddPesticide: (
    data: Journal.PesticideData,
  ) => Promise<Journal.CatalogAddResult<Journal.Pesticide>>;
  readonly onEditPesticide: (
    id: Journal.PesticideId,
    data: Journal.PesticideData,
  ) => Promise<Journal.CatalogEditResult<Journal.Pesticide>>;
  readonly onCancel: () => void;
}

type NomenclatureEditor =
  | {
      readonly kind: "substrate";
      readonly component: Journal.SubstrateComponent | undefined;
      readonly returnFocusId: string;
    }
  | {
      readonly kind: "pesticide";
      readonly pesticide: Journal.Pesticide | undefined;
      readonly returnFocusId: string;
    };

type Sheet = "editor" | "operation";

const sheetTransitionMilliseconds = 180;

export const OperationSheet: Component<OperationSheetProps> = (props) => {
  let dialog!: HTMLElement;
  const [editor, setEditor] = createSignal<NomenclatureEditor>();
  const [closingSheet, setClosingSheet] = createSignal<Sheet>();
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

  const closeEditor = (current: NomenclatureEditor) => {
    void closeSheets("editor", current);
  };

  const closeOperation = () => {
    void closeSheets("operation");
  };

  const closeOnEscape = (event: KeyboardEvent) => {
    if (event.key !== "Escape") return;
    const currentEditor = editor();
    if (currentEditor !== undefined) closeEditor(currentEditor);
    else closeOperation();
  };

  const editorContent = (current: NomenclatureEditor) =>
    current.kind === "substrate" ? (
      <SubstrateComponentEditor
        component={current.component}
        onAdd={props.onAddSubstrateComponent}
        onEdit={props.onEditSubstrateComponent}
        onClose={() => {
          closeEditor(current);
        }}
      />
    ) : (
      <PesticideEditor
        pesticide={current.pesticide}
        onAdd={props.onAddPesticide}
        onEdit={props.onEditPesticide}
        onClose={() => {
          closeEditor(current);
        }}
      />
    );

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
            class="sheet sheet--nomenclature-editor"
            classList={{ "sheet--closing": closingSheet() === "editor" }}
            inert={closingSheet() === "editor"}
            aria-label={
              current.kind === "substrate" ? "Substrate component editor" : "Pesticide editor"
            }
            aria-modal="true"
            role="dialog"
          >
            {editorContent(current)}
          </aside>
        )}
      </Show>
    </div>
  );
};
