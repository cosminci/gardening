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
import { PesticideCatalogForm } from "./PesticideCatalogForm";
import { SubstrateCatalogForm } from "./SubstrateCatalogForm";
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

export const OperationSheet: Component<OperationSheetProps> = (props) => {
  let dialog!: HTMLElement;
  const [catalog, setCatalog] = createSignal<"substrate" | "pesticide">();
  const initial = () => (props.target.kind === "edit" ? props.target.operation.details : undefined);
  const returnFocusId = () => operationControlId(props.target);
  const background = [...document.querySelectorAll<HTMLElement>(".masthead, .journal")];

  const closeCatalog = () => {
    const controlId =
      catalog() === "substrate" ? "manage-substrate-components" : "manage-pesticides";
    setCatalog(undefined);
    queueMicrotask(() => {
      document.getElementById(controlId)?.focus();
    });
  };

  const closeOnEscape = (event: KeyboardEvent) => {
    if (event.key !== "Escape") return;
    if (catalog() === undefined) props.onCancel();
    else closeCatalog();
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
    <div class="sheet-layer" classList={{ "sheet-layer--managing": catalog() !== undefined }}>
      <div class="sheet-layer__scrim" aria-hidden="true" />
      <aside
        ref={(element) => {
          dialog = element;
        }}
        class="sheet sheet--operation"
        aria-label="Operation editor"
        aria-modal={catalog() === undefined ? "true" : undefined}
        inert={catalog() !== undefined}
        role="dialog"
        tabIndex="-1"
      >
        <OperationForm
          initial={initial()}
          substrateComponents={props.substrateComponents}
          pesticides={props.pesticides}
          onSubmit={props.onSubmit}
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
          aria-label="Catalog manager"
          aria-modal="true"
          role="dialog"
        >
          <Switch>
            <Match when={catalog() === "substrate"}>
              <SubstrateCatalogForm
                components={props.substrateComponents}
                onAdd={props.onAddSubstrateComponent}
                onEdit={props.onEditSubstrateComponent}
                onClose={closeCatalog}
              />
            </Match>
            <Match when={catalog() === "pesticide"}>
              <PesticideCatalogForm
                pesticides={props.pesticides}
                onAdd={props.onAddPesticide}
                onEdit={props.onEditPesticide}
                onClose={closeCatalog}
              />
            </Match>
          </Switch>
        </aside>
      </Show>
    </div>
  );
};
