// Duration of the sheet open/close animation. Must stay in step with the
// `.sheet` transition in sheet.css: callers wait it out so focus is restored
// only after the sheet has finished sliding away.
export const sheetTransitionMilliseconds = 180;

export const waitForSheetTransition = () =>
  new Promise<void>((resolve) => {
    window.setTimeout(resolve, sheetTransitionMilliseconds);
  });
