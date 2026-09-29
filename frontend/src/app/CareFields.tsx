import { For, Show } from "solid-js";
import type { Component } from "solid-js";
import * as Journal from "../domain/Journal";
import { ActionGlyph } from "./ActionIcons";
import { InfoControl } from "./InfoControl";
import { actionLabels, moistureLabels, pesticideTypeLabels } from "./JournalLabels";
import { addPesticideControlId, editPesticideControlId } from "./OperationControlIds";
import "./plant-history.css";

interface CareFieldsProps {
  readonly actions: ReadonlySet<Journal.ActionType>;
  readonly moisture: Journal.MoistureLevel;
  readonly pesticides: readonly Journal.Pesticide[];
  readonly selectedPesticides: ReadonlySet<Journal.PesticideId>;
  readonly onActionChange: (action: Journal.ActionType, checked: boolean) => void;
  readonly onMoistureChange: (moisture: Journal.MoistureLevel) => void;
  readonly onPesticideChange: (pesticide: Journal.PesticideId, checked: boolean) => void;
  readonly onAddPesticide: () => void;
  readonly onEditPesticide: (pesticide: Journal.Pesticide) => void;
}

const selectableActions: readonly Exclude<Journal.ActionType, "noAction">[] = [
  ...Journal.actionTypes.filter(
    (action): action is Exclude<Journal.ActionType, "noAction" | "pesticide"> =>
      action !== "noAction" && action !== "pesticide",
  ),
  "pesticide",
];

export const CareFields: Component<CareFieldsProps> = (props) => (
  <>
    <label class="field">
      <span>Moisture reading</span>
      <select
        aria-label="Moisture"
        value={props.moisture}
        onChange={(event) => {
          props.onMoistureChange(event.currentTarget.value as Journal.MoistureLevel);
        }}
      >
        <For each={Journal.moistureLevels}>
          {(level) => <option value={level}>{moistureLabels[level]}</option>}
        </For>
      </select>
    </label>
    <fieldset class="field-group">
      <legend>Care actions</legend>
      <div class="action-grid">
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
              <span class="choice-action">
                <svg
                  class={`action-icon action-icon--${action}`}
                  viewBox="0 0 24 24"
                  aria-hidden="true"
                >
                  <ActionGlyph type={action} />
                </svg>
                {actionLabels[action]}
              </span>
            </label>
          )}
        </For>
      </div>
    </fieldset>
    <Show when={props.actions.has("pesticide")}>
      <fieldset class="field-group">
        <legend>Pesticides</legend>
        <div class="choice-grid">
          <For
            each={props.pesticides.filter(
              (pesticide) =>
                pesticide.status === "active" || props.selectedPesticides.has(pesticide.id),
            )}
          >
            {(pesticide) => (
              <div class="pesticide-choice">
                <span
                  class={`pesticide-type pesticide-type--${pesticide.data.type}`}
                  role="img"
                  aria-label={pesticideTypeLabels[pesticide.data.type]}
                  title={pesticideTypeLabels[pesticide.data.type]}
                >
                  {pesticideTypeLabels[pesticide.data.type].slice(0, 1)}
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
