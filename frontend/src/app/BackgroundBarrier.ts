import { onCleanup, onMount } from "solid-js";

// The barrier is shared: a confirmation dialog opens over an already-open sheet
// and each calls this hook. A per-caller boolean would let the inner dialog's
// cleanup un-inert the background while the outer sheet is still open, so the
// depth is ref-counted — the background inerts on the first entry and is only
// released once the last barrier user leaves.
let depth = 0;

const setBackgroundInert = (inert: boolean) => {
  document.querySelectorAll<HTMLElement>(".masthead, .journal").forEach((element) => {
    element.inert = inert;
  });
};

export const useBackgroundBarrier = (): void => {
  onMount(() => {
    if (++depth === 1) setBackgroundInert(true);
  });
  onCleanup(() => {
    if (--depth === 0) setBackgroundInert(false);
  });
};
