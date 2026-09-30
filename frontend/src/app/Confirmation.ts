import { createSignal } from "solid-js";
import type { Accessor } from "solid-js";

// A destructive-action confirmation: the target to act on, plus whether the
// action completed. Completion is tracked so the dialog's focus-restore can
// skip the trigger control the action removed. Opening always resets completion
// first, so reopening after a completed action restores focus normally.
export interface Confirmation<T> {
  readonly target: Accessor<T | undefined>;
  readonly completed: Accessor<boolean>;
  readonly request: (target: T) => void;
  readonly complete: () => void;
  readonly dismiss: () => void;
}

export const createConfirmation = <T>(): Confirmation<T> => {
  const [target, setTarget] = createSignal<T>();
  const [completed, setCompleted] = createSignal(false);
  return {
    target,
    completed,
    request: (value) => {
      setCompleted(false);
      setTarget(() => value);
    },
    complete: () => {
      setCompleted(true);
      setTarget(undefined);
    },
    dismiss: () => {
      setTarget(undefined);
    },
  };
};
