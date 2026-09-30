import { createSignal, onCleanup, onMount } from "solid-js";
import type { Accessor } from "solid-js";

// Below this width the plant history leaves the desktop layout — the 3-column
// recent row and the full history table both give way to a single stacked
// column of cards. Mirrors the `62rem` breakpoint in plant-history.css, shared
// so every component that adapts to the stacked view agrees on when it applies.
export const cardsBreakpoint = "(max-width: 62rem)";

// Tracks whether the stacked cards layout is active, reactively across resizes.
// jsdom has no matchMedia, so an absent implementation reads as the desktop view.
export const useCardsView = (): Accessor<boolean> => {
  const matches = () =>
    typeof window.matchMedia === "function" && window.matchMedia(cardsBreakpoint).matches;
  const [cardsView, setCardsView] = createSignal(matches());
  onMount(() => {
    if (typeof window.matchMedia !== "function") return;
    const query = window.matchMedia(cardsBreakpoint);
    const sync = (event: MediaQueryListEvent) => setCardsView(event.matches);
    query.addEventListener("change", sync);
    onCleanup(() => {
      query.removeEventListener("change", sync);
    });
  });
  return cardsView;
};
