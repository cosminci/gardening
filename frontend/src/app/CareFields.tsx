import { For, Show } from "solid-js";
import type { Component } from "solid-js";
import type { ActionType, MoistureLevel, Pesticide, PesticideId } from "../domain/Journal";
import { actionTypes, moistureLevels } from "../domain/Journal";
import { InfoControl } from "./InfoControl";
import { actionLabels, moistureLabels, pesticideTypeLabels } from "./JournalLabels";
import { addPesticideControlId, editPesticideControlId } from "./OperationControlIds";

interface CareFieldsProps {
  readonly actions: ReadonlySet<ActionType>;
  readonly moisture: MoistureLevel;
  readonly pesticides: readonly Pesticide[];
  readonly selectedPesticides: ReadonlySet<PesticideId>;
  readonly onActionChange: (action: ActionType, checked: boolean) => void;
  readonly onMoistureChange: (moisture: MoistureLevel) => void;
  readonly onPesticideChange: (pesticide: PesticideId, checked: boolean) => void;
  readonly onAddPesticide: () => void;
  readonly onEditPesticide: (pesticide: Pesticide) => void;
}

const selectableActions: readonly ActionType[] = [
  ...actionTypes.filter((action) => action !== "noAction" && action !== "pesticide"),
  "pesticide",
];

export const CareFields: Component<CareFieldsProps> = (props) => (
  <>
    <fieldset class="field-group">
      <legend>Care actions</legend>
      <div class="choice-grid">
        <For each={selectableActions}>
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
    <Show when={props.actions.has("pesticide")}>
      <fieldset class="field-group">
        <legend>Pesticides</legend>
        <div class="choice-grid">
          <For each={props.pesticides}>
            {(pesticide) => (
              <div class="pesticide-choice">
                <span
                  class={`pesticide-type pesticide-type--${pesticide.data.pesticideType}`}
                  role="img"
                  aria-label={pesticideTypeLabels[pesticide.data.pesticideType]}
                  title={pesticideTypeLabels[pesticide.data.pesticideType]}
                >
                  {pesticideTypeLabels[pesticide.data.pesticideType].slice(0, 1)}
                </span>
                <label class="choice">
                  <input
                    type="checkbox"
                    checked={props.selectedPesticides.has(pesticide.id)}
                    onChange={(event) => {
                      props.onPesticideChange(pesticide.id, event.currentTarget.checked);
                    }}
                  />
                  <span>{pesticide.data.name}</span>
                </label>
                <InfoControl
                  id={`pesticide-info-${pesticide.id}`}
                  label={`Information about ${pesticide.data.name}`}
                  notes={pesticide.data.maybeInfo}
                />
                <button
                  id={editPesticideControlId(pesticide.id)}
                  class="inline-icon-action inline-icon-action--edit"
                  type="button"
                  aria-label={`Edit ${pesticide.data.name}`}
                  onClick={() => {
                    props.onEditPesticide(pesticide);
                  }}
                />
              </div>
            )}
          </For>
        </div>
        <div class="field-group__actions catalog-actions">
          <button
            id={addPesticideControlId}
            class="compact-action catalog-action--define"
            type="button"
            onClick={() => {
              props.onAddPesticide();
            }}
          >
            Define new pesticide
          </button>
        </div>
      </fieldset>
    </Show>
  </>
);
