import { For, Show } from "solid-js";
import type { Component } from "solid-js";
import type { ActionType, MoistureLevel, Pesticide, PesticideId } from "../domain/Journal";
import { actionTypes, moistureLevels } from "../domain/Journal";
import { actionLabels, moistureLabels, pesticideTypeLabels } from "./JournalLabels";

interface CareFieldsProps {
  readonly actions: ReadonlySet<ActionType>;
  readonly moisture: MoistureLevel;
  readonly pesticides: readonly Pesticide[];
  readonly selectedPesticides: ReadonlySet<PesticideId>;
  readonly onActionChange: (action: ActionType, checked: boolean) => void;
  readonly onMoistureChange: (moisture: MoistureLevel) => void;
  readonly onPesticideChange: (pesticide: PesticideId, checked: boolean) => void;
  readonly onManagePesticides: () => void;
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
    <Show when={props.actions.has("pesticide")}>
      <fieldset class="field-group">
        <legend>Pesticides</legend>
        <div class="choice-grid">
          <For each={props.pesticides}>
            {(pesticide) => (
              <div class="choice-with-info">
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
                <span class="info-control">
                  <button
                    class="info-control__trigger"
                    type="button"
                    aria-label={`Information about ${pesticide.data.name}`}
                    aria-describedby={`pesticide-info-${pesticide.id}`}
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
                    id={`pesticide-info-${pesticide.id}`}
                    class="info-control__content"
                    role="tooltip"
                  >
                    <strong>{pesticideTypeLabels[pesticide.data.pesticideType]}</strong>{" "}
                    {pesticide.data.maybeInfo ?? "No notes."}
                  </span>
                </span>
              </div>
            )}
          </For>
        </div>
        <div class="field-group__actions">
          <button
            id="manage-pesticides"
            class="compact-action"
            type="button"
            onClick={() => {
              props.onManagePesticides();
            }}
          >
            Manage
          </button>
        </div>
      </fieldset>
    </Show>
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
