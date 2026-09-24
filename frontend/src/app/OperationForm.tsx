import { Show, createSignal, untrack } from "solid-js";
import type { Component } from "solid-js";
import * as Journal from "../domain/Journal";
import { CareFields } from "./CareFields";
import { SubstrateFields, validateSubstrate } from "./SubstrateFields";
import type { SubstratePartInput } from "./SubstrateFields";
import "./form-fields.css";
import "./operation-form.css";

interface OperationFormProps {
  readonly initial: Journal.OperationDetails | undefined;
  readonly substrateComponents: readonly Journal.SubstrateComponent[];
  readonly pesticides: readonly Journal.Pesticide[];
  readonly onSubmit: (details: Journal.OperationDetails, date: Journal.Instant) => Promise<void>;
  readonly onAddSubstrateComponent: () => void;
  readonly onEditSubstrateComponent: (
    component: Journal.SubstrateComponent,
    returnFocusId: string,
  ) => void;
  readonly onAddPesticide: () => void;
  readonly onEditPesticide: (pesticide: Journal.Pesticide) => void;
  readonly inactive?: boolean;
  readonly onCancel: () => void;
}

export const OperationForm: Component<OperationFormProps> = (props) => {
  const initialSubstrateComponent = untrack(() => props.substrateComponents[0]);
  const [kind, setKind] = createSignal(props.initial?.kind ?? "care");
  const [actions, setActions] = createSignal(
    props.initial?.kind === "care"
      ? new Set([...props.initial.actions].filter((action) => action !== "noAction"))
      : new Set<Journal.ActionType>(),
  );
  const [moisture, setMoisture] = createSignal(
    props.initial?.kind === "care" ? props.initial.moisture : "noReading",
  );
  const [selectedPesticides, setSelectedPesticides] = createSignal<
    ReadonlySet<Journal.PesticideId>
  >(props.initial?.kind === "care" ? new Set(props.initial.pesticides) : new Set());
  const [parts, setParts] = createSignal<SubstratePartInput[]>(
    props.initial?.kind === "repot"
      ? props.initial.substrate.map((part) => ({ component: part.component, share: part.share }))
      : initialSubstrateComponent === undefined
        ? []
        : [{ component: initialSubstrateComponent.id, share: 100 }],
  );
  const [notes, setNotes] = createSignal(props.initial?.maybeNote ?? "");
  const [operationDate, setOperationDate] = createSignal(formatLocalMinute(new Date()));
  const [dateError, setDateError] = createSignal(false);
  const [validationError, setValidationError] = createSignal<string>();
  const [submitting, setSubmitting] = createSignal(false);

  const toggleAction = (action: Journal.ActionType, checked: boolean) =>
    setActions((current) => {
      const updated = new Set(current);
      if (checked) updated.add(action);
      else updated.delete(action);
      if (!checked && action === "pesticide") setSelectedPesticides(new Set<Journal.PesticideId>());
      return updated;
    });

  const togglePesticide = (pesticide: Journal.PesticideId, checked: boolean) =>
    setSelectedPesticides((current) => {
      const updated = new Set(current);
      if (checked) updated.add(pesticide);
      else updated.delete(pesticide);
      return updated;
    });

  const submit = async (event: SubmitEvent) => {
    event.preventDefault();
    const date = parseLocalMinute(operationDate());
    setDateError(date === undefined);
    if (date === undefined) return;
    const error = kind() === "repot" ? validateSubstrate(parts()) : undefined;
    setValidationError(error);
    if (error !== undefined) return;

    const maybeNote = notes().trim() === "" ? null : Journal.note(notes().trim());
    const details: Journal.OperationDetails =
      kind() === "care"
        ? {
            kind: "care",
            actions: actions(),
            pesticides: selectedPesticides(),
            moisture: moisture(),
            maybeNote,
          }
        : {
            kind: "repot",
            substrate: Journal.substrate(
              parts().map((part) => ({
                component: part.component,
                share: Journal.percentage(part.share),
              })),
            ),
            maybeNote,
          };
    setSubmitting(true);
    try {
      await props.onSubmit(details, date);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <form
      class="operation-form"
      aria-label={props.initial === undefined ? "Log operation" : "Edit operation"}
      noValidate
      onSubmit={(event) => void submit(event)}
    >
      <header class="operation-form__header">
        <h2>{props.initial === undefined ? "Log operation" : "Edit operation"}</h2>
        <button
          class="icon-action sheet-collapse"
          type="button"
          aria-label="Collapse operation editor"
          onClick={() => {
            props.onCancel();
          }}
        >
          <span class="sheet-collapse__icon" aria-hidden="true" />
        </button>
      </header>

      <div class="operation-form__body" inert={props.inactive}>
        <Show when={props.initial === undefined}>
          <label class="field">
            <span>Date and time</span>
            <input
              type="datetime-local"
              value={operationDate()}
              step="60"
              aria-invalid={dateError()}
              aria-describedby={dateError() ? "operation-date-error" : undefined}
              onInput={(event) => {
                setOperationDate(event.currentTarget.value);
                setDateError(false);
              }}
            />
          </label>
          <Show when={dateError()}>
            <p id="operation-date-error" role="alert">
              Enter a valid date and time.
            </p>
          </Show>
        </Show>
        <label class="field">
          <span>Operation type</span>
          <select
            aria-label="Operation type"
            disabled={props.initial !== undefined}
            value={kind()}
            onChange={(event) =>
              setKind(event.currentTarget.value as Journal.OperationDetails["kind"])
            }
          >
            <option value="care">Care</option>
            <option value="repot">Repot</option>
          </select>
        </label>

        <Show
          when={kind() === "care"}
          fallback={
            <SubstrateFields
              parts={parts()}
              components={props.substrateComponents}
              onChange={setParts}
              onAddComponent={props.onAddSubstrateComponent}
              onEditComponent={props.onEditSubstrateComponent}
            />
          }
        >
          <CareFields
            actions={actions()}
            moisture={moisture()}
            pesticides={props.pesticides}
            selectedPesticides={selectedPesticides()}
            onActionChange={toggleAction}
            onMoistureChange={setMoisture}
            onPesticideChange={togglePesticide}
            onAddPesticide={props.onAddPesticide}
            onEditPesticide={props.onEditPesticide}
          />
        </Show>

        <label class="field">
          <span>Notes</span>
          <textarea
            aria-label="Notes"
            placeholder="Optional observations, quantities, or follow-up…"
            rows="4"
            value={notes()}
            onInput={(event) => {
              setNotes(event.currentTarget.value);
            }}
          />
        </label>
        <Show when={validationError()}>{(error) => <p role="alert">{error()}</p>}</Show>
        <footer class="operation-form__actions">
          <button class="primary-action" type="submit" disabled={submitting()}>
            {submitting() ? "Saving…" : "Save operation"}
          </button>
        </footer>
      </div>
    </form>
  );
};

const formatLocalMinute = (date: Date) => {
  const year = String(date.getFullYear()).padStart(4, "0");
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  const hour = String(date.getHours()).padStart(2, "0");
  const minute = String(date.getMinutes()).padStart(2, "0");
  return `${year}-${month}-${day}T${hour}:${minute}`;
};

const parseLocalMinute = (value: string): Journal.Instant | undefined => {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(value)) return undefined;
  const date = new Date(value);
  if (formatLocalMinute(date) !== value) return undefined;
  return Journal.instant(date.toISOString());
};
