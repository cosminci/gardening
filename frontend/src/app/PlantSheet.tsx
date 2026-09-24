import { Show, createSignal, onCleanup, onMount, untrack } from "solid-js";
import type { Component } from "solid-js";
import * as Journal from "../domain/Journal";
import { addSubstrateComponentControlId } from "./OperationControlIds";
import { SubstrateComponentEditor } from "./SubstrateComponentEditor";
import { SubstrateFields, validateSubstrate } from "./SubstrateFields";
import type { SubstratePartInput } from "./SubstrateFields";
import "./form-fields.css";
import "./operation-form.css";
import "./sheet.css";

interface PlantSheetProps {
  readonly components: readonly Journal.SubstrateComponent[];
  readonly saveError: string | undefined;
  readonly completed: boolean;
  readonly onSubmit: (details: Journal.NewPlantDetails) => Promise<void>;
  readonly onAddComponent: (
    data: Journal.SubstrateComponentData,
  ) => Promise<Journal.CatalogAddResult<Journal.SubstrateComponent>>;
  readonly onEditComponent: (
    id: Journal.SubstrateComponentId,
    data: Journal.SubstrateComponentData,
  ) => Promise<Journal.CatalogEditResult<Journal.SubstrateComponent>>;
  readonly onCancel: () => void;
}

export const PlantSheet: Component<PlantSheetProps> = (props) => {
  let dialog!: HTMLElement;
  let collapseButton!: HTMLButtonElement;
  let saveButton!: HTMLButtonElement;
  const firstComponent = untrack(() => props.components[0]);
  const [species, setSpecies] = createSignal("");
  const [nickname, setNickname] = createSignal("");
  const [location, setLocation] = createSignal("");
  const [parts, setParts] = createSignal<SubstratePartInput[]>(
    firstComponent === undefined ? [] : [{ component: firstComponent.id, share: 100 }],
  );
  const [validationError, setValidationError] = createSignal<string>();
  const [submitting, setSubmitting] = createSignal(false);
  const [editor, setEditor] = createSignal<{
    component: Journal.SubstrateComponent | undefined;
    returnFocusId: string;
  }>();
  const background = [...document.querySelectorAll<HTMLElement>(".masthead, .journal")];

  const closeEditor = () => {
    const controlId = editor()?.returnFocusId;
    setEditor(undefined);
    queueMicrotask(() => {
      if (controlId !== undefined) document.getElementById(controlId)?.focus();
    });
  };

  const onKeyDown = (event: KeyboardEvent) => {
    if (event.key === "Escape" && !submitting()) {
      event.preventDefault();
      if (editor() === undefined) props.onCancel();
      else closeEditor();
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

  onMount(() => {
    background.forEach((element) => {
      element.inert = true;
    });
    window.addEventListener("keydown", onKeyDown);
    dialog.focus();
  });

  onCleanup(() => {
    background.forEach((element) => {
      element.inert = false;
    });
    window.removeEventListener("keydown", onKeyDown);
    document.getElementById(props.completed ? "garden-toggle" : "add-plant")?.focus();
  });

  return (
    <div class="sheet-layer" classList={{ "sheet-layer--editing": editor() !== undefined }}>
      <div class="sheet-layer__scrim" aria-hidden="true" />
      <aside
        ref={(element) => {
          dialog = element;
        }}
        class="sheet sheet--operation sheet--plant sheet--entering"
        role="dialog"
        aria-label="Plant editor"
        aria-modal={editor() === undefined ? "true" : undefined}
        tabIndex="-1"
        inert={editor() !== undefined}
      >
        <form
          class="operation-form"
          aria-label="Add plant"
          noValidate
          onSubmit={(event) => void submit(event)}
        >
          <header class="operation-form__header">
            <h2>Add plant</h2>
            <button
              ref={(element) => {
                collapseButton = element;
              }}
              class="icon-action sheet-collapse"
              type="button"
              aria-label="Collapse plant editor"
              disabled={submitting()}
              onClick={() => {
                props.onCancel();
              }}
            >
              <span class="sheet-collapse__icon" aria-hidden="true" />
            </button>
          </header>
          <div class="operation-form__body">
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
                setEditor({ component: undefined, returnFocusId: addSubstrateComponentControlId })
              }
              onEditComponent={(component, returnFocusId) =>
                setEditor({ component, returnFocusId })
              }
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
                {submitting() ? "Saving…" : "Save plant"}
              </button>
            </footer>
          </div>
        </form>
      </aside>
      <Show keyed when={editor()}>
        {(current) => (
          <aside
            class="sheet sheet--nomenclature-editor"
            role="dialog"
            aria-modal="true"
            aria-label="Substrate component editor"
          >
            <SubstrateComponentEditor
              component={current.component}
              onAdd={addComponent}
              onEdit={editComponent}
              onClose={closeEditor}
            />
          </aside>
        )}
      </Show>
    </div>
  );
};
