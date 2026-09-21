import { For, Show, createSignal } from "solid-js";
import type { Component } from "solid-js";
import type {
  ActionType,
  CatalogAddResult,
  CatalogEditResult,
  MoistureLevel,
  Pesticide,
  PesticideData,
  PesticideId,
} from "../domain/Journal";
import {
  actionTypes,
  moistureLevels,
  nomenclatureInfo,
  nomenclatureName,
  pesticideType,
} from "../domain/Journal";
import { actionLabels, moistureLabels } from "./JournalLabels";

interface CareFieldsProps {
  readonly actions: ReadonlySet<ActionType>;
  readonly moisture: MoistureLevel;
  readonly pesticides: readonly Pesticide[];
  readonly selectedPesticides: ReadonlySet<PesticideId>;
  readonly onActionChange: (action: ActionType, checked: boolean) => void;
  readonly onMoistureChange: (moisture: MoistureLevel) => void;
  readonly onPesticideChange: (pesticide: PesticideId, checked: boolean) => void;
  readonly onAddPesticide: (data: PesticideData) => Promise<CatalogAddResult<Pesticide>>;
  readonly onEditPesticide: (
    id: PesticideId,
    data: PesticideData,
  ) => Promise<CatalogEditResult<Pesticide>>;
}

export const CareFields: Component<CareFieldsProps> = (props) => {
  const [newName, setNewName] = createSignal("");
  const [newType, setNewType] = createSignal("");
  const [newInfo, setNewInfo] = createSignal("");
  const [catalogError, setCatalogError] = createSignal<string>();

  const addPesticide = async () => {
    const name = newName().trim();
    const type = newType().trim();
    if (name === "" || type === "") {
      setCatalogError("Enter a pesticide name and type.");
      return;
    }
    try {
      const info = newInfo().trim();
      const result = await props.onAddPesticide({
        name: nomenclatureName(name),
        pesticideType: pesticideType(type),
        maybeInfo: info === "" ? null : nomenclatureInfo(info),
      });
      if (result.kind !== "added") {
        setCatalogError("The pesticide could not be saved.");
        return;
      }
      setNewName("");
      setNewType("");
      setNewInfo("");
      setCatalogError(undefined);
    } catch {
      setCatalogError("The pesticide could not be saved.");
    }
  };

  return (
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
      <Show when={props.actions.has("pesticide")}>
        <fieldset class="field-group">
          <legend>Pesticides</legend>
          <div class="choice-grid">
            <For each={props.pesticides}>
              {(pesticide) => (
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
              )}
            </For>
          </div>
          <details class="catalog-editor">
            <summary>Manage pesticides</summary>
            <div class="catalog-editor__entries">
              <For each={props.pesticides}>
                {(pesticide) => {
                  const [name, setName] = createSignal<string>(pesticide.data.name);
                  const [type, setType] = createSignal<string>(pesticide.data.pesticideType);
                  const [info, setInfo] = createSignal(pesticide.data.maybeInfo ?? "");
                  return (
                    <div class="catalog-editor__entry">
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
                        class="secondary-action"
                        type="button"
                        onClick={() => {
                          const updatedName = name().trim();
                          const updatedType = type().trim();
                          if (updatedName === "" || updatedType === "") {
                            setCatalogError("Enter a pesticide name and type.");
                            return;
                          }
                          const updatedInfo = info().trim();
                          void props
                            .onEditPesticide(pesticide.id, {
                              name: nomenclatureName(updatedName),
                              pesticideType: pesticideType(updatedType),
                              maybeInfo: updatedInfo === "" ? null : nomenclatureInfo(updatedInfo),
                            })
                            .then((result) => {
                              setCatalogError(
                                result.kind === "edited"
                                  ? undefined
                                  : result.kind === "recordMissing"
                                    ? "This pesticide no longer exists."
                                    : "The pesticide could not be saved.",
                              );
                            })
                            .catch(() => {
                              setCatalogError("The pesticide could not be saved.");
                            });
                        }}
                      >
                        Save {pesticide.data.name}
                      </button>
                    </div>
                  );
                }}
              </For>
              <div class="catalog-editor__entry">
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
                <button class="secondary-action" type="button" onClick={() => void addPesticide()}>
                  Add pesticide
                </button>
              </div>
            </div>
            <Show when={catalogError()}>{(error) => <p role="alert">{error()}</p>}</Show>
          </details>
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
};
