import { Show, createSignal, onMount, untrack } from "solid-js";
import type { Component } from "solid-js";
import * as Journal from "../domain/Journal";
import type { ArchiveAction } from "./PesticideEditor";
import "./catalog-editor.css";

interface SubstrateComponentEditorProps {
  readonly component: Journal.SubstrateComponent | undefined;
  readonly onAdd: (
    data: Journal.SubstrateComponentData,
  ) => Promise<Journal.CatalogAddResult<Journal.SubstrateComponent>>;
  readonly onEdit: (
    id: Journal.SubstrateComponent["id"],
    data: Journal.SubstrateComponentData,
  ) => Promise<Journal.SubstrateComponentEditResult>;
  readonly onArchive?: ArchiveAction | undefined;
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
      name: Journal.substrateComponentName(trimmedName),
      maybeInfo: trimmedInfo === "" ? null : Journal.substrateComponentInfo(trimmedInfo),
    };
    const result =
      props.component === undefined
        ? await props.onAdd(data)
        : await props.onEdit(props.component.id, data);
    // vitest 5's coverage-v8 (ast-v8-to-istanbul) miscounts this branch: it is fully covered in
    // isolation but the converter reports a negative else-count once this component's coverage is
    // merged with PlantSheet.componentTest's data. Exclude the phantom branch, not real logic.
    /* v8 ignore next */
    if (result.kind === "added" || result.kind === "edited") {
      props.onClose();
      return;
    }
    const errors = {
      componentMissing: "This substrate component no longer exists.",
      componentArchived: "This substrate component is archived and can no longer be edited.",
      addFailed: "The substrate component could not be saved.",
      editFailed: "The substrate component could not be saved.",
    } satisfies Record<typeof result.kind, string>;
    setError(errors[result.kind]);
  };

  const archived = () => props.component?.status === "archived";

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
      <header class="catalog-editor__header">
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
            disabled={archived()}
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
            disabled={archived()}
            onInput={(event) => {
              setInfo(event.currentTarget.value);
            }}
          />
        </label>
        <Show when={error()}>{(message) => <p role="alert">{message()}</p>}</Show>
        <Show when={!archived()} fallback={<p class="catalog-editor__archived-status">Archived</p>}>
          <footer class="catalog-editor__actions">
            <Show when={props.onArchive}>
              {(onArchive) => (
                <button
                  id={onArchive().controlId}
                  class="catalog-editor__archive"
                  type="button"
                  onClick={() => {
                    onArchive().onClick();
                  }}
                >
                  Archive
                </button>
              )}
            </Show>
            <button class="primary-action" type="submit">
              Save
            </button>
          </footer>
        </Show>
      </form>
    </section>
  );
};
