import { onCleanup, onMount } from "solid-js";

export const useBackgroundBarrier = (): void => {
  const background = [...document.querySelectorAll<HTMLElement>(".masthead, .journal")];
  onMount(() => {
    background.forEach((element) => {
      element.inert = true;
    });
  });
  onCleanup(() => {
    background.forEach((element) => {
      element.inert = false;
    });
  });
};
