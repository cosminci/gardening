import { For, Show, createSignal, onMount } from "solid-js";
import type { Component } from "solid-js";
import type {
  CatalogAddResult,
  CatalogEditResult,
  Pesticide,
  PesticideData,
  PesticideId,
} from "../domain/Journal";
import { nomenclatureInfo, nomenclatureName, pesticideType } from "../domain/Journal";

interface PesticideCatalogFormProps {
  readonly pesticides: readonly Pesticide[];
  readonly onAdd: (data: PesticideData) => Promise<CatalogAddResult<Pesticide>>;
  readonly onEdit: (id: PesticideId, data: PesticideData) => Promise<CatalogEditResult<Pesticide>>;
  readonly onClose: () => void;
}

export const PesticideCatalogForm: Component<PesticideCatalogFormProps> = (props) => {
  let panel!: HTMLElement;
  const [newName, setNewName] = createSignal("");
  const [newType, setNewType] = createSignal("");
  const [newInfo, setNewInfo] = createSignal("");
  const [error, setError] = createSignal<string>();

  const add = async () => {
    const name = newName().trim();
    const type = newType().trim();
    if (name === "" || type === "") {
      setError("Enter a pesticide name and type.");
      return;
    }
    const info = newInfo().trim();
    const result = await props.onAdd({
      name: nomenclatureName(name),
      pesticideType: pesticideType(type),
      maybeInfo: info === "" ? null : nomenclatureInfo(info),
    });
    if (result.kind === "added") {
      setNewName("");
      setNewType("");
      setNewInfo("");
      setError(undefined);
    } else setError("The pesticide could not be saved.");
  };

  const edit = async (id: PesticideId, nameValue: string, typeValue: string, infoValue: string) => {
    const name = nameValue.trim();
    const type = typeValue.trim();
    if (name === "" || type === "") {
      setError("Enter a pesticide name and type.");
      return;
    }
    const info = infoValue.trim();
    const result = await props.onEdit(id, {
      name: nomenclatureName(name),
      pesticideType: pesticideType(type),
      maybeInfo: info === "" ? null : nomenclatureInfo(info),
    });
    setError(
      result.kind === "edited"
        ? undefined
        : result.kind === "recordMissing"
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
          class="icon-action"
          type="button"
          aria-label="Close pesticide management"
          onClick={() => {
            props.onClose();
          }}
        >
          <span aria-hidden="true">×</span>
        </button>
      </header>
      <div class="catalog-form__entries">
        <For each={props.pesticides}>
          {(pesticide) => {
            const [name, setName] = createSignal<string>(pesticide.data.name);
            const [type, setType] = createSignal<string>(pesticide.data.pesticideType);
            const [info, setInfo] = createSignal(pesticide.data.maybeInfo ?? "");
            return (
              <form
                class="catalog-form__entry"
                aria-label={`Edit ${pesticide.data.name}`}
                onSubmit={(event) => {
                  event.preventDefault();
                  void edit(pesticide.id, name(), type(), info());
                }}
              >
                <label class="field">
                  <span>Name</span>
                  <input
                    aria-label={`Name for ${pesticide.data.name}`}
                    type="text"
                    value={name()}
                    onInput={(event) => {
                      setName(event.currentTarget.value);
                    }}
                  />
                </label>
                <label class="field">
                  <span>Type</span>
                  <input
                    aria-label={`Type for ${pesticide.data.name}`}
                    type="text"
                    value={type()}
                    onInput={(event) => {
                      setType(event.currentTarget.value);
                    }}
                  />
                </label>
                <label class="field">
                  <span>Info</span>
                  <input
                    aria-label={`Info for ${pesticide.data.name}`}
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
                  aria-label={`Save ${pesticide.data.name}`}
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
        aria-label="Add pesticide"
        onSubmit={(event) => {
          event.preventDefault();
          void add();
        }}
      >
        <label class="field">
          <span>New pesticide</span>
          <input
            aria-label="New pesticide name"
            type="text"
            value={newName()}
            onInput={(event) => {
              setNewName(event.currentTarget.value);
            }}
          />
        </label>
        <label class="field">
          <span>Type</span>
          <input
            aria-label="New pesticide type"
            type="text"
            value={newType()}
            onInput={(event) => {
              setNewType(event.currentTarget.value);
            }}
          />
        </label>
        <label class="field">
          <span>Info</span>
          <input
            aria-label="New pesticide info"
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
