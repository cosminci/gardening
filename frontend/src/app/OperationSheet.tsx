import { Match, Show, Switch, createSignal, onCleanup, onMount } from "solid-js";
import type { Component } from "solid-js";
import type {
  CatalogAddResult,
  CatalogEditResult,
  Operation,
  OperationDetails,
  Pesticide,
  PesticideData,
  PesticideId,
  PlantId,
  SubstrateComponent,
  SubstrateComponentData,
  SubstrateComponentId,
} from "../domain/Journal";
import { editOperationControlId, logOperationControlId } from "./OperationControlIds";
import { OperationForm } from "./OperationForm";
import { PesticideCatalog, PesticideCatalogEditor } from "./PesticideCatalog";
import { SubstrateCatalog, SubstrateCatalogEditor } from "./SubstrateCatalog";
import "./catalog-form.css";
import "./sheet.css";

export type OperationTarget =
  | { readonly kind: "log"; readonly plantId: PlantId }
  | { readonly kind: "edit"; readonly operation: Operation };

export const operationControlId = (target: OperationTarget) =>
  target.kind === "log"
    ? logOperationControlId(target.plantId)
    : editOperationControlId(target.operation.id);

interface OperationSheetProps {
  readonly target: OperationTarget;
  readonly substrateComponents: readonly SubstrateComponent[];
  readonly pesticides: readonly Pesticide[];
  readonly saveError: string | undefined;
  readonly onSubmit: (details: OperationDetails) => Promise<void>;
  readonly onAddSubstrateComponent: (
    data: SubstrateComponentData,
  ) => Promise<CatalogAddResult<SubstrateComponent>>;
  readonly onEditSubstrateComponent: (
    id: SubstrateComponentId,
    data: SubstrateComponentData,
  ) => Promise<CatalogEditResult<SubstrateComponent>>;
  readonly onAddPesticide: (data: PesticideData) => Promise<CatalogAddResult<Pesticide>>;
  readonly onEditPesticide: (
    id: PesticideId,
    data: PesticideData,
  ) => Promise<CatalogEditResult<Pesticide>>;
  readonly onCancel: () => void;
}

type CatalogEditor =
  | { readonly kind: "substrate"; readonly component: SubstrateComponent | undefined }
  | { readonly kind: "pesticide"; readonly pesticide: Pesticide | undefined };

export const OperationSheet: Component<OperationSheetProps> = (props) => {
  let dialog!: HTMLElement;
  const [catalog, setCatalog] = createSignal<"substrate" | "pesticide">();
  const [editor, setEditor] = createSignal<CatalogEditor>();
  const initial = () => (props.target.kind === "edit" ? props.target.operation.details : undefined);
  const returnFocusId = () => operationControlId(props.target);
  const background = [...document.querySelectorAll<HTMLElement>(".masthead, .journal")];

  const closeCatalog = () => {
    const controlId =
      catalog() === "substrate" ? "manage-substrate-components" : "manage-pesticides";
    setEditor(undefined);
    setCatalog(undefined);
    queueMicrotask(() => {
      document.getElementById(controlId)?.focus();
    });
  };

  const closeEditor = (current: CatalogEditor) => {
    const controlId =
      current.kind === "substrate"
        ? current.component === undefined
          ? "add-substrate-component"
          : `edit-substrate-component-${current.component.id}`
        : current.pesticide === undefined
          ? "add-pesticide"
          : `edit-pesticide-${current.pesticide.id}`;
    setEditor(undefined);
    queueMicrotask(() => {
      document.getElementById(controlId)?.focus();
    });
  };

  const closeOnEscape = (event: KeyboardEvent) => {
    if (event.key !== "Escape") return;
    const currentEditor = editor();
    if (currentEditor !== undefined) closeEditor(currentEditor);
    else if (catalog() === undefined) props.onCancel();
    else closeCatalog();
  };

  const editorContent = (current: CatalogEditor) =>
    current.kind === "substrate" ? (
      <SubstrateCatalogEditor
        component={current.component}
        onAdd={props.onAddSubstrateComponent}
        onEdit={props.onEditSubstrateComponent}
        onClose={() => {
          closeEditor(current);
        }}
      />
    ) : (
      <PesticideCatalogEditor
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
        "sheet-layer--managing": catalog() !== undefined,
        "sheet-layer--editing-catalog": editor() !== undefined,
      }}
    >
      <div class="sheet-layer__scrim" aria-hidden="true" />
      <aside
        ref={(element) => {
          dialog = element;
        }}
        class="sheet sheet--operation"
        aria-label="Operation editor"
        aria-modal={catalog() === undefined ? "true" : undefined}
        role="dialog"
        tabIndex="-1"
      >
        <OperationForm
          initial={initial()}
          substrateComponents={props.substrateComponents}
          pesticides={props.pesticides}
          onSubmit={props.onSubmit}
          inactive={catalog() !== undefined}
          onManageSubstrateComponents={() => {
            setCatalog("substrate");
          }}
          onManagePesticides={() => {
            setCatalog("pesticide");
          }}
          onCancel={props.onCancel}
        />
        <Show when={props.saveError}>
          {(message) => (
            <p class="inline-alert" role="alert">
              {message()}
            </p>
          )}
        </Show>
      </aside>
      <Show when={catalog()}>
        <aside
          class="sheet sheet--catalog"
          aria-label={catalog() === "substrate" ? "Substrate catalog" : "Pesticide catalog"}
          aria-modal={editor() === undefined ? "true" : undefined}
          role="dialog"
        >
          <Switch>
            <Match when={catalog() === "substrate"}>
              <SubstrateCatalog
                components={props.substrateComponents}
                inactive={editor() !== undefined}
                onAdd={() => {
                  setEditor({ kind: "substrate", component: undefined });
                }}
                onEdit={(component) => {
                  setEditor({ kind: "substrate", component });
                }}
                onClose={closeCatalog}
              />
            </Match>
            <Match when={catalog() === "pesticide"}>
              <PesticideCatalog
                pesticides={props.pesticides}
                inactive={editor() !== undefined}
                onAdd={() => {
                  setEditor({ kind: "pesticide", pesticide: undefined });
                }}
                onEdit={(pesticide) => {
                  setEditor({ kind: "pesticide", pesticide });
                }}
                onClose={closeCatalog}
              />
            </Match>
          </Switch>
        </aside>
      </Show>
      <Show keyed when={editor()}>
        {(current) => (
          <aside
            class="sheet sheet--catalog-editor"
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
