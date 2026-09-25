import { For, Show, createSignal, onMount, untrack } from "solid-js";
import type { Component } from "solid-js";
import * as Journal from "../domain/Journal";
import { pesticideTypeLabels } from "./JournalLabels";
import "./nomenclature-editor.css";

export interface ArchiveAction {
  readonly controlId: string;
  readonly onClick: () => void;
}

interface PesticideEditorProps {
  readonly pesticide: Journal.Pesticide | undefined;
  readonly onAdd: (
    data: Journal.PesticideData,
  ) => Promise<Journal.CatalogAddResult<Journal.Pesticide>>;
  readonly onEdit: (
    id: Journal.Pesticide["id"],
    data: Journal.PesticideData,
  ) => Promise<Journal.PesticideEditResult>;
  readonly onArchive?: ArchiveAction | undefined;
  readonly onClose: () => void;
}

export const PesticideEditor: Component<PesticideEditorProps> = (props) => {
  let panel!: HTMLElement;
  const initial = untrack(() => props.pesticide);
  const [name, setName] = createSignal<string>(initial?.data.name ?? "");
  const [type, setType] = createSignal<Journal.PesticideType>(
    initial?.data.pesticideType ?? "fungicide",
  );
  const [info, setInfo] = createSignal(initial?.data.maybeInfo ?? "");
  const [error, setError] = createSignal<string>();

  const save = async (): Promise<void> => {
    const trimmedName = name().trim();
    if (trimmedName === "") {
      setError("Enter a pesticide name.");
      return;
    }
    const trimmedInfo = info().trim();
    const data = {
      name: Journal.pesticideName(trimmedName),
      pesticideType: type(),
      maybeInfo: trimmedInfo === "" ? null : Journal.pesticideInfo(trimmedInfo),
    };
    const result =
      props.pesticide === undefined
        ? await props.onAdd(data)
        : await props.onEdit(props.pesticide.id, data);
    if (result.kind === "added" || result.kind === "edited") {
      props.onClose();
      return;
    }
    const errors = {
      pesticideMissing: "This pesticide no longer exists.",
      pesticideArchived: "This pesticide is archived and can no longer be edited.",
      addFailed: "The pesticide could not be saved.",
      editFailed: "The pesticide could not be saved.",
    } satisfies Record<typeof result.kind, string>;
    setError(errors[result.kind]);
  };

  const archived = () => props.pesticide?.status === "archived";

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
        props.pesticide === undefined ? "Add pesticide" : `Edit ${props.pesticide.data.name}`
      }
      tabIndex="-1"
    >
      <header class="nomenclature-editor__header">
        <h2>{props.pesticide === undefined ? "Add pesticide" : "Edit pesticide"}</h2>
        <button
          class="icon-action sheet-collapse"
          type="button"
          aria-label="Collapse pesticide editor"
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
          props.pesticide === undefined ? "Add pesticide" : `Edit ${props.pesticide.data.name}`
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
          <span>Type</span>
          <select
            aria-label="Type"
            value={type()}
            disabled={archived()}
            onChange={(event) => {
              setType(event.currentTarget.value as Journal.PesticideType);
            }}
          >
            <For each={Journal.pesticideTypes}>
              {(value) => <option value={value}>{pesticideTypeLabels[value]}</option>}
            </For>
          </select>
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
        <Show
          when={!archived()}
          fallback={<p class="nomenclature-editor__archived-status">Archived</p>}
        >
          <footer class="nomenclature-editor__actions">
            <Show when={props.onArchive}>
              {(onArchive) => (
                <button
                  id={onArchive().controlId}
                  class="nomenclature-editor__archive"
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
