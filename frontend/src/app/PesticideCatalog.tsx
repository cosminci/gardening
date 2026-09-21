import { For, Show, createSignal, onMount, untrack } from "solid-js";
import type { Component } from "solid-js";
import type {
  CatalogAddResult,
  CatalogEditResult,
  Pesticide,
  PesticideData,
  PesticideType,
} from "../domain/Journal";
import { nomenclatureInfo, nomenclatureName, pesticideTypes } from "../domain/Journal";
import { pesticideTypeLabels } from "./JournalLabels";

interface PesticideCatalogProps {
  readonly pesticides: readonly Pesticide[];
  readonly inactive?: boolean;
  readonly onAdd: () => void;
  readonly onEdit: (pesticide: Pesticide) => void;
  readonly onClose: () => void;
}

export const PesticideCatalog: Component<PesticideCatalogProps> = (props) => {
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
      aria-label="Manage pesticides"
      tabIndex="-1"
    >
      <header class="catalog-form__header">
        <div>
          <p class="eyebrow">Nomenclature</p>
          <h2>Pesticides</h2>
        </div>
        <button
          class="icon-action sheet-collapse"
          type="button"
          aria-label="Collapse pesticide catalog"
          onClick={() => {
            props.onClose();
          }}
        >
          <span aria-hidden="true">×</span>
        </button>
      </header>
      <div class="catalog-form__body" inert={props.inactive}>
        <div class="catalog-table-container">
          <table class="catalog-table" aria-label="Pesticides">
            <thead>
              <tr>
                <th scope="col">Name</th>
                <th scope="col">Type</th>
                <th scope="col">Info</th>
                <th scope="col">Actions</th>
              </tr>
            </thead>
            <tbody>
              <For each={props.pesticides}>
                {(pesticide) => (
                  <tr>
                    <th scope="row">{pesticide.data.name}</th>
                    <td>{pesticideTypeLabels[pesticide.data.pesticideType]}</td>
                    <td class="catalog-table__info">{pesticide.data.maybeInfo ?? "No notes."}</td>
                    <td>
                      <button
                        id={`edit-pesticide-${pesticide.id}`}
                        class="compact-action"
                        type="button"
                        aria-label={`Edit ${pesticide.data.name}`}
                        onClick={() => {
                          props.onEdit(pesticide);
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
            id="add-pesticide"
            class="secondary-action"
            type="button"
            onClick={() => {
              props.onAdd();
            }}
          >
            Add pesticide
          </button>
        </footer>
      </div>
    </section>
  );
};

interface PesticideCatalogEditorProps {
  readonly pesticide: Pesticide | undefined;
  readonly onAdd: (data: PesticideData) => Promise<CatalogAddResult<Pesticide>>;
  readonly onEdit: (
    id: Pesticide["id"],
    data: PesticideData,
  ) => Promise<CatalogEditResult<Pesticide>>;
  readonly onClose: () => void;
}

export const PesticideCatalogEditor: Component<PesticideCatalogEditorProps> = (props) => {
  let panel!: HTMLElement;
  const initial = untrack(() => props.pesticide);
  const [name, setName] = createSignal<string>(initial?.data.name ?? "");
  const [type, setType] = createSignal<PesticideType>(initial?.data.pesticideType ?? "fungicide");
  const [info, setInfo] = createSignal(initial?.data.maybeInfo ?? "");
  const [error, setError] = createSignal<string>();

  const save = async () => {
    const trimmedName = name().trim();
    if (trimmedName === "") {
      setError("Enter a pesticide name.");
      return;
    }
    const trimmedInfo = info().trim();
    const data = {
      name: nomenclatureName(trimmedName),
      pesticideType: type(),
      maybeInfo: trimmedInfo === "" ? null : nomenclatureInfo(trimmedInfo),
    };
    const result =
      props.pesticide === undefined
        ? await props.onAdd(data)
        : await props.onEdit(props.pesticide.id, data);
    if (result.kind === "added" || result.kind === "edited") props.onClose();
    else
      setError(
        result.kind === "recordMissing"
          ? "This pesticide no longer exists."
          : "The pesticide could not be saved.",
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
        props.pesticide === undefined ? "Add pesticide" : `Edit ${props.pesticide.data.name}`
      }
      tabIndex="-1"
    >
      <header class="catalog-form__header">
        <div>
          <p class="eyebrow">Pesticide</p>
          <h2>{props.pesticide === undefined ? "Add pesticide" : "Edit pesticide"}</h2>
        </div>
        <button
          class="icon-action sheet-collapse"
          type="button"
          aria-label="Collapse pesticide editor"
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
            onChange={(event) => {
              setType(event.currentTarget.value as PesticideType);
            }}
          >
            <For each={pesticideTypes}>
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
