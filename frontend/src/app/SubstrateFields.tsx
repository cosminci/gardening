import { For, Index, Show, createSignal } from "solid-js";
import type { Component } from "solid-js";
import type {
  CatalogAddResult,
  CatalogEditResult,
  SubstrateComponent,
  SubstrateComponentData,
  SubstrateComponentId,
} from "../domain/Journal";
import { nomenclatureInfo, nomenclatureName } from "../domain/Journal";
import { substrateComponentLabel } from "./JournalLabels";

export interface SubstratePartInput {
  readonly component: SubstrateComponentId;
  readonly share: number;
}

interface SubstrateFieldsProps {
  readonly parts: readonly SubstratePartInput[];
  readonly components: readonly SubstrateComponent[];
  readonly onChange: (parts: SubstratePartInput[]) => void;
  readonly onAddComponent: (
    data: SubstrateComponentData,
  ) => Promise<CatalogAddResult<SubstrateComponent>>;
  readonly onEditComponent: (
    id: SubstrateComponentId,
    data: SubstrateComponentData,
  ) => Promise<CatalogEditResult<SubstrateComponent>>;
}

export const SubstrateFields: Component<SubstrateFieldsProps> = (props) => {
  const [newName, setNewName] = createSignal("");
  const [newInfo, setNewInfo] = createSignal("");
  const [catalogError, setCatalogError] = createSignal<string>();

  const updatePart = (index: number, update: Partial<SubstratePartInput>) => {
    props.onChange(
      props.parts.map((part, partIndex) => (partIndex === index ? { ...part, ...update } : part)),
    );
  };

  const addComponent = async () => {
    const name = newName().trim();
    if (name === "") {
      setCatalogError("Enter a component name.");
      return;
    }
    try {
      const info = newInfo().trim();
      const result = await props.onAddComponent({
        name: nomenclatureName(name),
        maybeInfo: info === "" ? null : nomenclatureInfo(info),
      });
      if (result.kind !== "added") {
        setCatalogError("The substrate component could not be saved.");
        return;
      }
      setNewName("");
      setNewInfo("");
      setCatalogError(undefined);
    } catch {
      setCatalogError("The substrate component could not be saved.");
    }
  };

  const availableComponent = () =>
    props.components.find(
      (component) => !props.parts.some((part) => part.component === component.id),
    );

  return (
    <fieldset class="field-group">
      <legend>Substrate mix</legend>
      <Index each={props.parts}>
        {(part, index) => (
          <div class="substrate-row">
            <label class="field">
              <span>Component</span>
              <select
                aria-label={`Component ${String(index + 1)}`}
                value={part().component}
                onChange={(event) => {
                  updatePart(index, {
                    component: event.currentTarget.value as SubstrateComponentId,
                  });
                }}
              >
                <For each={props.components}>
                  {(component) => (
                    <option value={component.id}>
                      {substrateComponentLabel(component.id, props.components)}
                    </option>
                  )}
                </For>
              </select>
            </label>
            <label class="field field--share">
              <span>Share</span>
              <input
                aria-label={`Component ${String(index + 1)} share`}
                type="number"
                min="1"
                max="100"
                required
                value={part().share}
                onInput={(event) => {
                  updatePart(index, { share: event.currentTarget.valueAsNumber });
                }}
              />
            </label>
            <button
              class="icon-action"
              type="button"
              aria-label={`Remove component ${String(index + 1)}`}
              title={`Remove ${substrateComponentLabel(part().component, props.components)}`}
              disabled={props.parts.length === 1}
              onClick={() => {
                props.onChange(props.parts.filter((_, partIndex) => partIndex !== index));
              }}
            >
              <span aria-hidden="true">×</span>
            </button>
          </div>
        )}
      </Index>
      <button
        class="secondary-action add-component"
        type="button"
        aria-label="Add component"
        disabled={availableComponent() === undefined}
        onClick={() => {
          const component = availableComponent();
          if (component !== undefined)
            props.onChange([...props.parts, { component: component.id, share: 1 }]);
        }}
      >
        + Add component
      </button>
      <details class="catalog-editor">
        <summary>Manage substrate components</summary>
        <div class="catalog-editor__entries">
          <For each={props.components}>
            {(component) => {
              const [name, setName] = createSignal<string>(component.data.name);
              const [info, setInfo] = createSignal(component.data.maybeInfo ?? "");
              return (
                <div class="catalog-editor__entry">
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
                    class="secondary-action"
                    type="button"
                    onClick={() => {
                      const updatedName = name().trim();
                      if (updatedName === "") {
                        setCatalogError("Enter a component name.");
                        return;
                      }
                      const updatedInfo = info().trim();
                      void props
                        .onEditComponent(component.id, {
                          name: nomenclatureName(updatedName),
                          maybeInfo: updatedInfo === "" ? null : nomenclatureInfo(updatedInfo),
                        })
                        .then((result) => {
                          setCatalogError(
                            result.kind === "edited"
                              ? undefined
                              : result.kind === "recordMissing"
                                ? "This substrate component no longer exists."
                                : "The substrate component could not be saved.",
                          );
                        })
                        .catch(() => {
                          setCatalogError("The substrate component could not be saved.");
                        });
                    }}
                  >
                    Save {component.data.name}
                  </button>
                </div>
              );
            }}
          </For>
          <div class="catalog-editor__entry">
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
            <button class="secondary-action" type="button" onClick={() => void addComponent()}>
              Add substrate component
            </button>
          </div>
        </div>
        <Show when={catalogError()}>{(error) => <p role="alert">{error()}</p>}</Show>
      </details>
    </fieldset>
  );
};
