import { For, Match, Show, Switch, createEffect, createSignal, on } from "solid-js";
import type { Component } from "solid-js";
import type * as Journal from "../domain/Journal";
import * as Labels from "./JournalLabels";
import { editOperationControlId } from "./OperationControlIds";
import { OperationCell } from "./OperationCell";

export type OperationHistoryChange =
  | { readonly kind: "logged" }
  | { readonly kind: "deleted" }
  | { readonly kind: "edited"; readonly operation: Journal.Operation };

interface OperationHistoryProps {
  readonly plant: Journal.PlantId;
  readonly substrateComponents: readonly Journal.SubstrateComponent[];
  readonly pesticides: readonly Journal.Pesticide[];
  readonly getOperations: (window: Journal.OperationWindow) => Promise<Journal.GetOperationsResult>;
  readonly operationChange: OperationHistoryChange | undefined;
  readonly onEdit: (operation: Journal.Operation) => void;
}

type HistoryState =
  | { readonly kind: "idle" }
  | { readonly kind: "loading" }
  | { readonly kind: "failed" }
  | { readonly kind: "loaded"; readonly page: Journal.OperationPage };

export const recentOperationCount = 3;
const historyPageSize = 10;

export const OperationHistory: Component<OperationHistoryProps> = (props) => {
  const [expanded, setExpanded] = createSignal(false);
  const [pageNumber, setPageNumber] = createSignal(1);
  const [state, setState] = createSignal<HistoryState>({ kind: "idle" });
  let latestRequest = 0;
  let requestedPage = 1;
  let control!: HTMLButtonElement;
  let disclosure!: HTMLDivElement;
  let pageStatus: HTMLSpanElement | undefined;
  let failureStatus: HTMLParagraphElement | undefined;
  let pendingScrollRestore: ((event: TransitionEvent) => void) | undefined;

  const loadPage = async (page: number, focusResult = false) => {
    const request = ++latestRequest;
    requestedPage = page;
    setState({ kind: "loading" });
    const outcome = await props
      .getOperations({
        offset: recentOperationCount + (page - 1) * historyPageSize,
        size: historyPageSize,
      })
      .then(
        (result) => ({ kind: "completed", result }) as const,
        () => ({ kind: "rejected" }) as const,
      );
    if (request !== latestRequest) return;
    if (outcome.kind === "completed" && outcome.result.kind === "read") {
      setPageNumber(page);
      setState({ kind: "loaded", page: outcome.result.page });
      if (focusResult) queueMicrotask(() => pageStatus?.focus());
    } else {
      setState({ kind: "failed" });
      if (focusResult) queueMicrotask(() => failureStatus?.focus());
    }
  };

  const toggle = () => {
    const opening = !expanded();
    const anchorBefore = control.getBoundingClientRect().top;
    setExpanded(opening);
    if (opening) void loadPage(1);
    if (!opening) {
      if (pendingScrollRestore)
        disclosure.removeEventListener("transitionend", pendingScrollRestore);
      const restoreScrollPosition = (event: TransitionEvent) => {
        if (event.target !== disclosure || event.propertyName !== "grid-template-rows") return;
        disclosure.removeEventListener("transitionend", restoreScrollPosition);
        pendingScrollRestore = undefined;
        const drift = control.getBoundingClientRect().top - anchorBefore;
        if (drift !== 0) window.scrollBy(0, drift);
      };
      pendingScrollRestore = restoreScrollPosition;
      disclosure.addEventListener("transitionend", restoreScrollPosition);
    }
    queueMicrotask(() => {
      control.focus({ preventScroll: true });
    });
  };

  createEffect(
    on(
      () => props.plant,
      () => {
        latestRequest += 1;
        requestedPage = 1;
        setExpanded(false);
        setPageNumber(1);
        setState({ kind: "idle" });
      },
      { defer: true },
    ),
  );

  createEffect(
    on(
      () => props.operationChange,
      (change) => {
        if (!expanded() || change === undefined) return;
        if (change.kind === "logged" || change.kind === "deleted") void loadPage(pageNumber());
        else
          setState((current) =>
            current.kind === "loaded"
              ? {
                  kind: "loaded",
                  page: {
                    ...current.page,
                    operations: current.page.operations.map((operation) =>
                      operation.id === change.operation.id ? change.operation : operation,
                    ),
                  },
                }
              : current,
          );
      },
      { defer: true },
    ),
  );

  const loadedPage = () => {
    const current = state();
    return current.kind === "loaded" ? current.page : undefined;
  };

  return (
    <section class="operation-history">
      <div
        ref={(element) => {
          disclosure = element;
        }}
        id={`operation-history-${props.plant}`}
        class="operation-history__disclosure"
        classList={{ "operation-history__disclosure--expanded": expanded() }}
        aria-label="Operation history"
        aria-hidden={!expanded()}
        inert={!expanded()}
      >
        <div class="operation-history__content">
          <Switch>
            <Match when={state().kind === "loading"}>
              <p class="operation-history__state">Loading operation history…</p>
            </Match>
            <Match when={state().kind === "failed"}>
              <p
                ref={(element) => {
                  failureStatus = element;
                }}
                class="operation-history__state"
                role="alert"
                tabindex="-1"
              >
                Operation history could not be loaded.
              </p>
              <button
                class="compact-action"
                type="button"
                onClick={() => void loadPage(requestedPage, true)}
              >
                Retry
              </button>
            </Match>
            <Match when={loadedPage()} keyed>
              {(page) => (
                <div class="operation-history__page">
                  <Show
                    when={page.operations.length > 0}
                    fallback={<p class="operation-history__state">No older operations.</p>}
                  >
                    <ol
                      class="operation-list operation-history__cards"
                      aria-label="Older operations"
                    >
                      <For each={page.operations}>
                        {(operation, index) => (
                          <OperationCell
                            operation={operation}
                            position={index() + 1}
                            section="historical"
                            substrateComponents={props.substrateComponents}
                            pesticides={props.pesticides}
                            onEdit={() => {
                              props.onEdit(operation);
                            }}
                          />
                        )}
                      </For>
                    </ol>
                    <div class="operation-history__table-wrap">
                      <table>
                        <thead>
                          <tr>
                            <th scope="col">
                              <span class="visually-hidden">Edit</span>
                            </th>
                            <th scope="col">Date</th>
                            <th scope="col">Type</th>
                            <th scope="col">Details</th>
                            <th scope="col">Notes</th>
                          </tr>
                        </thead>
                        <tbody>
                          <For each={page.operations}>
                            {(operation, index) => (
                              <tr
                                class={`operation-history__row operation--${operation.details.kind}`}
                              >
                                <td class="operation-history__edit-cell">
                                  <button
                                    id={editOperationControlId(operation.id)}
                                    class="inline-icon-action inline-icon-action--edit"
                                    type="button"
                                    aria-label={Labels.operationEditLabel(
                                      operation,
                                      index() + 1,
                                      "historical",
                                    )}
                                    onClick={() => {
                                      props.onEdit(operation);
                                    }}
                                  />
                                </td>
                                <td>
                                  <time dateTime={operation.date}>
                                    {Labels.formatLocalDate(operation.date)}
                                  </time>
                                </td>
                                <td>
                                  <span class="operation__kind">
                                    {Labels.operationKindLabel(operation.details)}
                                  </span>
                                </td>
                                <td>
                                  <For
                                    each={Labels.operationDetailRows(
                                      operation.details,
                                      props.substrateComponents,
                                      props.pesticides,
                                    )}
                                  >
                                    {(detail) => (
                                      <span class="operation-history__detail">
                                        <strong>{detail.label}:</strong> {detail.value}
                                      </span>
                                    )}
                                  </For>
                                </td>
                                <td>{operation.details.maybeNote ?? "—"}</td>
                              </tr>
                            )}
                          </For>
                        </tbody>
                      </table>
                    </div>
                  </Show>
                  <nav class="operation-history__pagination" aria-label="Operation history pages">
                    <button
                      class="compact-action"
                      type="button"
                      disabled={pageNumber() === 1}
                      onClick={() => void loadPage(pageNumber() - 1, true)}
                    >
                      Previous
                    </button>
                    <span
                      ref={(element) => {
                        pageStatus = element;
                      }}
                      tabindex="-1"
                      aria-live="polite"
                    >
                      Page {pageNumber()}
                    </span>
                    <button
                      class="compact-action"
                      type="button"
                      disabled={!page.hasNextPage}
                      onClick={() => void loadPage(pageNumber() + 1, true)}
                    >
                      Next
                    </button>
                  </nav>
                </div>
              )}
            </Match>
          </Switch>
        </div>
      </div>
      <button
        ref={(element) => {
          control = element;
        }}
        class="operation-history__toggle"
        type="button"
        aria-label={expanded() ? "Hide operation history" : "Show operation history"}
        aria-expanded={expanded()}
        aria-controls={`operation-history-${props.plant}`}
        onClick={toggle}
      >
        <span aria-hidden="true">
          <i />
          <i />
          <i />
        </span>
      </button>
    </section>
  );
};
