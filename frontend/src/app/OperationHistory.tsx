import { For, Match, Show, Switch, createEffect, createSignal, on } from "solid-js";
import type { Component } from "solid-js";
import type * as Journal from "../domain/Journal";
import * as Labels from "./JournalLabels";
import { editOperationControlId } from "./OperationControlIds";

export type OperationHistoryChange =
  { readonly kind: "logged" } | { readonly kind: "edited"; readonly operation: Journal.Operation };

interface OperationHistoryProps {
  readonly plantId: Journal.PlantId;
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
  let pageStatus: HTMLSpanElement | undefined;
  let failureStatus: HTMLParagraphElement | undefined;

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
    setExpanded(opening);
    if (opening) void loadPage(1);
    queueMicrotask(() => {
      control.focus();
    });
  };

  createEffect(
    on(
      () => props.operationChange,
      (change) => {
        if (!expanded() || change === undefined) return;
        if (change.kind === "logged") void loadPage(pageNumber());
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
        id={`operation-history-${props.plantId}`}
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
                    <div class="operation-history__table-wrap">
                      <table>
                        <thead>
                          <tr>
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
                                <td>
                                  <time dateTime={operation.date}>
                                    {Labels.formatLocalDate(operation.date)}
                                  </time>
                                </td>
                                <td>
                                  <span class="operation__kind">
                                    {Labels.operationKindLabel(operation.details)}
                                  </span>
                                  <button
                                    id={editOperationControlId(operation.id)}
                                    class="operation__edit"
                                    type="button"
                                    aria-label={Labels.operationEditLabel(
                                      operation,
                                      index() + 1,
                                      "historical",
                                    )}
                                    onClick={() => {
                                      props.onEdit(operation);
                                    }}
                                  >
                                    Edit
                                  </button>
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
        aria-controls={`operation-history-${props.plantId}`}
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
