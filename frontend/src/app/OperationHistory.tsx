import { For, Match, Show, Switch, createEffect, createSignal, on } from "solid-js";
import type { Component } from "solid-js";
import type * as Journal from "../domain/Journal";
import * as Labels from "./JournalLabels";
import { editOperationControlId } from "./OperationControlIds";

export type OperationHistoryChange =
  | { readonly kind: "logged"; readonly revision: number }
  | { readonly kind: "edited"; readonly revision: number; readonly operation: Journal.Operation };

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

const historyPageSize = 10;

export const OperationHistory: Component<OperationHistoryProps> = (props) => {
  const [expanded, setExpanded] = createSignal(false);
  const [pageNumber, setPageNumber] = createSignal(1);
  const [state, setState] = createSignal<HistoryState>({ kind: "idle" });
  let latestRequest = 0;

  const loadPage = async (page: number) => {
    const request = ++latestRequest;
    setState({ kind: "loading" });
    const outcome = await props
      .getOperations({
        offset: 3 + (page - 1) * historyPageSize,
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
    } else setState({ kind: "failed" });
  };

  const toggle = () => {
    const opening = !expanded();
    setExpanded(opening);
    if (opening) void loadPage(1);
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

  const controlButton = (
    <button
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
  );

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
              <p class="operation-history__state" role="alert">
                Operation history could not be loaded.
              </p>
              <button type="button" onClick={() => void loadPage(pageNumber())}>
                Retry
              </button>
            </Match>
            <Match when={loadedPage()} keyed>
              {(page) => {
                return (
                  <>
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
                              {(operation) => (
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
                                      aria-label={`Edit ${operation.details.kind} operation from ${Labels.formatLocalDate(operation.date)}`}
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
                      <nav
                        class="operation-history__pagination"
                        aria-label="Operation history pages"
                      >
                        <button
                          type="button"
                          disabled={pageNumber() === 1}
                          onClick={() => void loadPage(pageNumber() - 1)}
                        >
                          Previous
                        </button>
                        <span aria-live="polite">Page {pageNumber()}</span>
                        <button
                          type="button"
                          disabled={!page.hasNextPage}
                          onClick={() => void loadPage(pageNumber() + 1)}
                        >
                          Next
                        </button>
                      </nav>
                    </Show>
                  </>
                );
              }}
            </Match>
          </Switch>
        </div>
      </div>
      {controlButton}
    </section>
  );
};
