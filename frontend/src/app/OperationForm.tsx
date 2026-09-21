import { Show, createSignal, untrack } from "solid-js";
import type { Component } from "solid-js";
import type {
  ActionType,
  OperationDetails,
  Pesticide,
  PesticideId,
  SubstrateComponent,
} from "../domain/Journal";
import { note, percentage, substrate } from "../domain/Journal";
import { CareFields } from "./CareFields";
import { SubstrateFields } from "./SubstrateFields";
import type { SubstratePartInput } from "./SubstrateFields";
import "./form-fields.css";
import "./operation-form.css";

interface OperationFormProps {
  readonly initial: OperationDetails | undefined;
  readonly substrateComponents: readonly SubstrateComponent[];
  readonly pesticides: readonly Pesticide[];
  readonly onSubmit: (details: OperationDetails) => Promise<void>;
  readonly onManageSubstrateComponents: () => void;
  readonly onManagePesticides: () => void;
  readonly inactive?: boolean;
  readonly onCancel: () => void;
}

export const OperationForm: Component<OperationFormProps> = (props) => {
  const initialSubstrateComponent = untrack(() => props.substrateComponents[0]);
  const [kind, setKind] = createSignal(props.initial?.kind ?? "care");
  const [actions, setActions] = createSignal(
    props.initial?.kind === "care"
      ? new Set([...props.initial.actions].filter((action) => action !== "noAction"))
      : new Set<ActionType>(),
  );
  const [moisture, setMoisture] = createSignal(
    props.initial?.kind === "care" ? props.initial.moisture : "noReading",
  );
  const [selectedPesticides, setSelectedPesticides] = createSignal<ReadonlySet<PesticideId>>(
    props.initial?.kind === "care" ? new Set(props.initial.pesticides) : new Set(),
  );
  const [parts, setParts] = createSignal<SubstratePartInput[]>(
    props.initial?.kind === "repot"
      ? props.initial.substrate.map((part) => ({ component: part.component, share: part.share }))
      : initialSubstrateComponent === undefined
        ? []
        : [{ component: initialSubstrateComponent.id, share: 100 }],
  );
  const [notes, setNotes] = createSignal(props.initial?.maybeNote ?? "");
  const [validationError, setValidationError] = createSignal<string>();
  const [submitting, setSubmitting] = createSignal(false);

  const toggleAction = (action: ActionType, checked: boolean) =>
    setActions((current) => {
      const updated = new Set(current);
      if (checked) updated.add(action);
      else updated.delete(action);
      if (!checked && action === "pesticide") setSelectedPesticides(new Set<PesticideId>());
      return updated;
    });

  const togglePesticide = (pesticide: PesticideId, checked: boolean) =>
    setSelectedPesticides((current) => {
      const updated = new Set(current);
      if (checked) updated.add(pesticide);
      else updated.delete(pesticide);
      return updated;
    });

  const submit = async (event: SubmitEvent) => {
    event.preventDefault();
    const error = kind() === "repot" ? validateSubstrate(parts()) : undefined;
    setValidationError(error);
    if (error !== undefined) return;

    const maybeNote = notes().trim() === "" ? null : note(notes().trim());
    const details: OperationDetails =
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
            substrate: substrate(
              parts().map((part) => ({ component: part.component, share: percentage(part.share) })),
            ),
            maybeNote,
          };
    setSubmitting(true);
    try {
      await props.onSubmit(details);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <form
      class="operation-form"
      aria-label={props.initial === undefined ? "Log operation" : "Edit operation"}
      onSubmit={(event) => void submit(event)}
    >
      <header class="operation-form__header">
        <div>
          <p class="eyebrow">{props.initial === undefined ? "New journal entry" : "Amend entry"}</p>
          <h2>{props.initial === undefined ? "Log plant care" : "Edit operation"}</h2>
          <p>Record what changed while the details are still fresh.</p>
        </div>
        <button
          class="icon-action sheet-collapse"
          type="button"
          aria-label="Collapse operation editor"
          onClick={() => {
            props.onCancel();
          }}
        >
          <span aria-hidden="true">×</span>
        </button>
      </header>

      <div class="operation-form__body" inert={props.inactive}>
        <label class="field">
          <span>Operation type</span>
          <select
            aria-label="Operation type"
            disabled={props.initial !== undefined}
            value={kind()}
            onChange={(event) => setKind(event.currentTarget.value as OperationDetails["kind"])}
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
              onManageComponents={props.onManageSubstrateComponents}
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
            onManagePesticides={props.onManagePesticides}
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

const validateSubstrate = (parts: readonly SubstratePartInput[]) => {
  if (parts.length === 0) return "Add at least one substrate component.";
  if (parts.some((part) => !Number.isInteger(part.share) || part.share < 1 || part.share > 100))
    return "Each substrate share must be a whole number from 1 to 100%.";
  if (new Set(parts.map((part) => part.component)).size !== parts.length)
    return "Each substrate component can only be used once.";
  if (parts.reduce((total, part) => total + part.share, 0) > 100)
    return "Substrate shares cannot total more than 100%.";
  return undefined;
};
