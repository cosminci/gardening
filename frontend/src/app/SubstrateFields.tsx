import { For, Index, Show } from "solid-js";
import type { Component } from "solid-js";
import type { SubstrateComponent, SubstrateComponentId } from "../domain/Journal";
import { InfoControl } from "./InfoControl";
import { substrateComponentLabel } from "./JournalLabels";
import {
  addSubstrateComponentControlId,
  editSubstrateComponentControlId,
} from "./OperationControlIds";

export interface SubstratePartInput {
  readonly component: SubstrateComponentId;
  readonly share: number;
}

interface SubstrateFieldsProps {
  readonly parts: readonly SubstratePartInput[];
  readonly components: readonly SubstrateComponent[];
  readonly onChange: (parts: SubstratePartInput[]) => void;
  readonly onAddComponent: () => void;
  readonly onEditComponent: (component: SubstrateComponent, returnFocusId: string) => void;
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
              <Show when={selectedComponent(part().component)}>
                {(component) => {
                  const editControlId = editSubstrateComponentControlId(index, component().id);
                  return (
                    <>
                      <InfoControl
                        id={`substrate-info-${String(index)}-${component().id}`}
                        label={`Information about ${component().data.name}`}
                        notes={component().data.maybeInfo}
                      />
                      <button
                        id={editControlId}
                        class="inline-icon-action inline-icon-action--edit"
                        type="button"
                        aria-label={`Edit ${component().data.name}`}
                        onClick={() => {
                          props.onEditComponent(component(), editControlId);
                        }}
                      />
                    </>
                  );
                }}
              </Show>
            </div>
            <div class="percentage-input">
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
              <span aria-hidden="true">%</span>
            </div>
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
      <div class="field-group__actions catalog-actions">
        <button
          class="compact-action"
          type="button"
          aria-label="Extend mix"
          disabled={availableComponent() === undefined}
          onClick={() => {
            const component = availableComponent();
            if (component !== undefined)
              props.onChange([...props.parts, { component: component.id, share: 1 }]);
          }}
        >
          Extend mix
        </button>
        <button
          id={addSubstrateComponentControlId}
          class="compact-action catalog-action--define"
          type="button"
          onClick={() => {
            props.onAddComponent();
          }}
        >
          Define new component
        </button>
      </div>
    </fieldset>
  );
};
