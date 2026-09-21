import { For, Show, createSignal, onMount, untrack } from "solid-js";
import type { Component } from "solid-js";
import type {
  CatalogAddResult,
  CatalogEditResult,
  SubstrateComponent,
  SubstrateComponentData,
} from "../domain/Journal";
import { nomenclatureInfo, nomenclatureName } from "../domain/Journal";

interface SubstrateCatalogProps {
  readonly components: readonly SubstrateComponent[];
  readonly inactive?: boolean;
  readonly onAdd: () => void;
  readonly onEdit: (component: SubstrateComponent) => void;
  readonly onClose: () => void;
}

export const SubstrateCatalog: Component<SubstrateCatalogProps> = (props) => {
  let panel!: HTMLElement;

  onMount(() => {
    panel.focus();
  });

  return (
    <section
      ref={(element) => {
        panel = element;
      }}
      class="catalog-form"
      aria-label="Manage substrate components"
      tabIndex="-1"
    >
      <header class="catalog-form__header">
        <div>
          <p class="eyebrow">Nomenclature</p>
          <h2>Substrate components</h2>
        </div>
        <button
          class="icon-action sheet-collapse"
          type="button"
          aria-label="Collapse substrate catalog"
          onClick={() => {
            props.onClose();
          }}
        >
          <span aria-hidden="true">×</span>
        </button>
      </header>
      <div class="catalog-form__body" inert={props.inactive}>
        <div class="catalog-table-container">
          <table class="catalog-table" aria-label="Substrate components">
            <thead>
              <tr>
                <th scope="col">Name</th>
                <th scope="col">Info</th>
                <th scope="col">Actions</th>
              </tr>
            </thead>
            <tbody>
              <For each={props.components}>
                {(component) => (
                  <tr>
                    <th scope="row">{component.data.name}</th>
                    <td class="catalog-table__info">{component.data.maybeInfo ?? "No notes."}</td>
                    <td>
                      <button
                        id={`edit-substrate-component-${component.id}`}
                        class="compact-action"
                        type="button"
                        aria-label={`Edit ${component.data.name}`}
                        onClick={() => {
                          props.onEdit(component);
                        }}
                      >
                        Edit
                      </button>
                    </td>
                  </tr>
                )}
              </For>
            </tbody>
          </table>
        </div>
        <footer class="catalog-form__actions">
          <button
            id="add-substrate-component"
            class="secondary-action"
            type="button"
            onClick={() => {
              props.onAdd();
            }}
          >
            Add substrate component
          </button>
        </footer>
      </div>
    </section>
  );
};

interface SubstrateCatalogEditorProps {
  readonly component: SubstrateComponent | undefined;
  readonly onAdd: (data: SubstrateComponentData) => Promise<CatalogAddResult<SubstrateComponent>>;
  readonly onEdit: (
    id: SubstrateComponent["id"],
    data: SubstrateComponentData,
  ) => Promise<CatalogEditResult<SubstrateComponent>>;
  readonly onClose: () => void;
}

export const SubstrateCatalogEditor: Component<SubstrateCatalogEditorProps> = (props) => {
  let panel!: HTMLElement;
  const initial = untrack(() => props.component);
  const [name, setName] = createSignal<string>(initial?.data.name ?? "");
  const [info, setInfo] = createSignal(initial?.data.maybeInfo ?? "");
  const [error, setError] = createSignal<string>();

  const save = async () => {
    const trimmedName = name().trim();
    if (trimmedName === "") {
      setError("Enter a component name.");
      return;
    }
    const trimmedInfo = info().trim();
    const data = {
      name: nomenclatureName(trimmedName),
      maybeInfo: trimmedInfo === "" ? null : nomenclatureInfo(trimmedInfo),
    };
    const result =
      props.component === undefined
        ? await props.onAdd(data)
        : await props.onEdit(props.component.id, data);
    if (result.kind === "added" || result.kind === "edited") props.onClose();
    else
      setError(
        result.kind === "recordMissing"
          ? "This substrate component no longer exists."
          : "The substrate component could not be saved.",
      );
  };

  onMount(() => {
    panel.focus();
  });

  return (
    <section
      ref={(element) => {
        panel = element;
      }}
      class="catalog-editor"
      aria-label={
        props.component === undefined
          ? "Add substrate component"
          : `Edit ${props.component.data.name}`
      }
      tabIndex="-1"
    >
      <header class="catalog-form__header">
        <div>
          <p class="eyebrow">Substrate component</p>
          <h2>{props.component === undefined ? "Add component" : "Edit component"}</h2>
        </div>
        <button
          class="icon-action sheet-collapse"
          type="button"
          aria-label="Collapse substrate editor"
          onClick={() => {
            props.onClose();
          }}
        >
          <span aria-hidden="true">×</span>
        </button>
      </header>
      <form
        class="catalog-editor__form"
        aria-label={
          props.component === undefined
            ? "Add substrate component"
            : `Edit ${props.component.data.name}`
        }
        onSubmit={(event) => {
          event.preventDefault();
          void save();
        }}
      >
        <label class="field">
          <span>Name</span>
          <input
            aria-label="Name"
            type="text"
            value={name()}
            onInput={(event) => {
              setName(event.currentTarget.value);
            }}
          />
        </label>
        <label class="field">
          <span>Info</span>
          <textarea
            aria-label="Info"
            rows="10"
            value={info()}
            onInput={(event) => {
              setInfo(event.currentTarget.value);
            }}
          />
        </label>
        <Show when={error()}>{(message) => <p role="alert">{message()}</p>}</Show>
        <footer class="catalog-editor__actions">
          <button class="primary-action" type="submit">
            Save
          </button>
        </footer>
      </form>
    </section>
  );
};
