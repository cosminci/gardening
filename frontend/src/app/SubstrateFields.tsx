import { For, Index, Show } from "solid-js";
import type { Component } from "solid-js";
import type { SubstrateComponent, SubstrateComponentId } from "../domain/Journal";
import { substrateComponentLabel } from "./JournalLabels";

export interface SubstratePartInput {
  readonly component: SubstrateComponentId;
  readonly share: number;
}

interface SubstrateFieldsProps {
  readonly parts: readonly SubstratePartInput[];
  readonly components: readonly SubstrateComponent[];
  readonly onChange: (parts: SubstratePartInput[]) => void;
  readonly onManageComponents: () => void;
}

export const SubstrateFields: Component<SubstrateFieldsProps> = (props) => {
  const updatePart = (index: number, update: Partial<SubstratePartInput>) => {
    props.onChange(
      props.parts.map((part, partIndex) => (partIndex === index ? { ...part, ...update } : part)),
    );
  };

  const availableComponent = () =>
    props.components.find(
      (component) => !props.parts.some((part) => part.component === component.id),
    );
  const selectedComponent = (id: SubstrateComponentId) =>
    props.components.find((component) => component.id === id);

  return (
    <fieldset class="field-group">
      <legend>Substrate mix</legend>
      <Index each={props.parts}>
        {(part, index) => (
          <div class="substrate-row">
            <div class="substrate-component">
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
              <Show when={selectedComponent(part().component)}>
                {(component) => (
                  <span class="info-control">
                    <button
                      class="info-control__trigger"
                      type="button"
                      aria-label={`Information about ${component().data.name}`}
                      aria-describedby={`substrate-info-${String(index)}-${component().id}`}
                      onKeyDown={(event) => {
                        if (event.key === "Escape") {
                          event.stopPropagation();
                          event.currentTarget.blur();
                        }
                      }}
                    >
                      i
                    </button>
                    <span
                      id={`substrate-info-${String(index)}-${component().id}`}
                      class="info-control__content"
                      role="tooltip"
                    >
                      {component().data.maybeInfo ?? "No notes."}
                    </span>
                  </span>
                )}
              </Show>
            </div>
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
      <div class="field-group__actions">
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
        <button
          id="manage-substrate-components"
          class="compact-action"
          type="button"
          onClick={() => {
            props.onManageComponents();
          }}
        >
          Manage
        </button>
      </div>
    </fieldset>
  );
};
