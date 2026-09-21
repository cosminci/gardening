import { For } from "solid-js";
import type { Component } from "solid-js";
import type { ActionType, MoistureLevel } from "../domain/Journal";
import { actionTypes, moistureLevels } from "../domain/Journal";
import { actionLabels, moistureLabels } from "./JournalLabels";

interface CareFieldsProps {
  readonly actions: ReadonlySet<ActionType>;
  readonly moisture: MoistureLevel;
  readonly onActionChange: (action: ActionType, checked: boolean) => void;
  readonly onMoistureChange: (moisture: MoistureLevel) => void;
}

export const CareFields: Component<CareFieldsProps> = (props) => (
  <>
    <fieldset class="field-group">
      <legend>Care actions</legend>
      <div class="choice-grid">
        <For each={actionTypes}>
          {(action) => (
            <label class="choice">
              <input
                type="checkbox"
                checked={props.actions.has(action)}
                onChange={(event) => {
                  props.onActionChange(action, event.currentTarget.checked);
                }}
              />
              <span>{actionLabels[action]}</span>
            </label>
          )}
        </For>
      </div>
    </fieldset>
    <label class="field">
      <span>Moisture reading</span>
      <select
        aria-label="Moisture"
        value={props.moisture}
        onChange={(event) => {
          props.onMoistureChange(event.currentTarget.value as MoistureLevel);
        }}
      >
        <For each={moistureLevels}>
          {(level) => <option value={level}>{moistureLabels[level]}</option>}
        </For>
      </select>
    </label>
  </>
);
