import { Show, createSignal, onMount, untrack } from "solid-js";
import type { Component } from "solid-js";
import type {
  CatalogAddResult,
  CatalogEditResult,
  SubstrateComponent,
  SubstrateComponentData,
} from "../domain/Journal";
import { nomenclatureInfo, nomenclatureName } from "../domain/Journal";
import "./nomenclature-editor.css";

interface SubstrateComponentEditorProps {
  readonly component: SubstrateComponent | undefined;
  readonly onAdd: (data: SubstrateComponentData) => Promise<CatalogAddResult<SubstrateComponent>>;
  readonly onEdit: (
    id: SubstrateComponent["id"],
    data: SubstrateComponentData,
  ) => Promise<CatalogEditResult<SubstrateComponent>>;
  readonly onClose: () => void;
}

export const SubstrateComponentEditor: Component<SubstrateComponentEditorProps> = (props) => {
  let panel!: HTMLElement;
  const initial = untrack(() => props.component);
  const [name, setName] = createSignal<string>(initial?.data.name ?? "");
  const [info, setInfo] = createSignal(initial?.data.maybeInfo ?? "");
  const [error, setError] = createSignal<string>();

  const save = async (): Promise<void> => {
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
    if (result.kind === "added" || result.kind === "edited") {
      props.onClose();
      return;
    }
    const errors = {
      recordMissing: "This substrate component no longer exists.",
      addFailed: "The substrate component could not be saved.",
      editFailed: "The substrate component could not be saved.",
    } satisfies Record<typeof result.kind, string>;
    setError(errors[result.kind]);
  };

  onMount(() => {
    panel.focus();
  });

  return (
    <section
      ref={(element) => {
        panel = element;
      }}
      class="nomenclature-editor"
      aria-label={
        props.component === undefined
          ? "Add substrate component"
          : `Edit ${props.component.data.name}`
      }
      tabIndex="-1"
    >
      <header class="nomenclature-editor__header">
        <h2>
          {props.component === undefined ? "Add substrate component" : "Edit substrate component"}
        </h2>
        <button
          class="icon-action sheet-collapse"
          type="button"
          aria-label="Collapse substrate editor"
          onClick={() => {
            props.onClose();
          }}
        >
          <span class="sheet-collapse__icon" aria-hidden="true" />
        </button>
      </header>
      <form
        class="nomenclature-editor__form"
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
        <footer class="nomenclature-editor__actions">
          <button class="primary-action" type="submit">
            Save
          </button>
        </footer>
      </form>
    </section>
  );
};
