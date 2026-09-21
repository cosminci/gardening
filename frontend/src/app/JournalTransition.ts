interface TransitionDocument {
  readonly startViewTransition?: Document["startViewTransition"];
}

export const displayJournalUpdate = async (animate: boolean, update: () => void) => {
  const startViewTransition = (document as TransitionDocument).startViewTransition;
  if (animate && startViewTransition)
    await startViewTransition.call(document, update).updateCallbackDone;
  else update();
};
