import { For, Show, createSignal, onMount } from "solid-js";
import type { Component } from "solid-js";
import type {
  CatalogAddResult,
  CatalogEditResult,
  SubstrateComponent,
  SubstrateComponentData,
  SubstrateComponentId,
} from "../domain/Journal";
import { nomenclatureInfo, nomenclatureName } from "../domain/Journal";

interface SubstrateCatalogFormProps {
  readonly components: readonly SubstrateComponent[];
  readonly onAdd: (data: SubstrateComponentData) => Promise<CatalogAddResult<SubstrateComponent>>;
  readonly onEdit: (
    id: SubstrateComponentId,
    data: SubstrateComponentData,
  ) => Promise<CatalogEditResult<SubstrateComponent>>;
  readonly onClose: () => void;
}

export const SubstrateCatalogForm: Component<SubstrateCatalogFormProps> = (props) => {
  let panel!: HTMLElement;
  const [newName, setNewName] = createSignal("");
  const [newInfo, setNewInfo] = createSignal("");
  const [error, setError] = createSignal<string>();

  const add = async () => {
    const name = newName().trim();
    if (name === "") {
      setError("Enter a component name.");
      return;
    }
    const info = newInfo().trim();
    const result = await props.onAdd({
      name: nomenclatureName(name),
      maybeInfo: info === "" ? null : nomenclatureInfo(info),
    });
    if (result.kind === "added") {
      setNewName("");
      setNewInfo("");
      setError(undefined);
    } else setError("The substrate component could not be saved.");
  };

  const edit = async (id: SubstrateComponentId, nameValue: string, infoValue: string) => {
    const name = nameValue.trim();
    if (name === "") {
      setError("Enter a component name.");
      return;
    }
    const info = infoValue.trim();
    const result = await props.onEdit(id, {
      name: nomenclatureName(name),
      maybeInfo: info === "" ? null : nomenclatureInfo(info),
    });
    setError(
      result.kind === "edited"
        ? undefined
        : result.kind === "recordMissing"
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
          class="icon-action"
          type="button"
          aria-label="Close substrate management"
          onClick={() => {
            props.onClose();
          }}
        >
          <span aria-hidden="true">×</span>
        </button>
      </header>
      <div class="catalog-form__entries">
        <For each={props.components}>
          {(component) => {
            const [name, setName] = createSignal<string>(component.data.name);
            const [info, setInfo] = createSignal(component.data.maybeInfo ?? "");
            return (
              <form
                class="catalog-form__entry"
                aria-label={`Edit ${component.data.name}`}
                onSubmit={(event) => {
                  event.preventDefault();
                  void edit(component.id, name(), info());
                }}
              >
                <label class="field">
                  <span>Name</span>
                  <input
                    aria-label={`Name for ${component.data.name}`}
                    type="text"
                    value={name()}
                    onInput={(event) => {
                      setName(event.currentTarget.value);
                    }}
                  />
                </label>
                <label class="field">
                  <span>Info</span>
                  <input
                    aria-label={`Info for ${component.data.name}`}
                    type="text"
                    value={info()}
                    onInput={(event) => {
                      setInfo(event.currentTarget.value);
                    }}
                  />
                </label>
                <button
                  class="compact-action"
                  type="submit"
                  aria-label={`Save ${component.data.name}`}
                >
                  Save
                </button>
              </form>
            );
          }}
        </For>
      </div>
      <form
        class="catalog-form__entry catalog-form__entry--new"
        aria-label="Add substrate component"
        onSubmit={(event) => {
          event.preventDefault();
          void add();
        }}
      >
        <label class="field">
          <span>New component</span>
          <input
            aria-label="New substrate component name"
            type="text"
            value={newName()}
            onInput={(event) => {
              setNewName(event.currentTarget.value);
            }}
          />
        </label>
        <label class="field">
          <span>Info</span>
          <input
            aria-label="New substrate component info"
            type="text"
            value={newInfo()}
            onInput={(event) => {
              setNewInfo(event.currentTarget.value);
            }}
          />
        </label>
        <button class="compact-action" type="submit">
          Add
        </button>
      </form>
      <Show when={error()}>{(message) => <p role="alert">{message()}</p>}</Show>
    </section>
  );
};
