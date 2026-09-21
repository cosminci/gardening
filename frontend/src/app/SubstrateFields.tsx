import { For, Index } from "solid-js";
import type { Component } from "solid-js";
import type { SubstrateComponent } from "../domain/Journal";
import { substrateComponents } from "../domain/Journal";
import { substrateComponentLabels } from "./JournalLabels";

export interface SubstratePartInput {
  readonly component: SubstrateComponent;
  readonly share: number;
}

interface SubstrateFieldsProps {
  readonly parts: readonly SubstratePartInput[];
  readonly onChange: (parts: SubstratePartInput[]) => void;
}

export const SubstrateFields: Component<SubstrateFieldsProps> = (props) => {
  const updatePart = (index: number, update: Partial<SubstratePartInput>) => {
    props.onChange(
      props.parts.map((part, partIndex) => (partIndex === index ? { ...part, ...update } : part)),
    );
  };

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
                    component: event.currentTarget.value as SubstrateComponent,
                  });
                }}
              >
                <For each={substrateComponents}>
                  {(component) => (
                    <option value={component}>{substrateComponentLabels[component]}</option>
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
              title={`Remove ${substrateComponentLabels[part().component]}`}
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
        onClick={() => {
          props.onChange([...props.parts, { component: "perlite", share: 1 }]);
        }}
      >
        + Add component
      </button>
    </fieldset>
  );
};
