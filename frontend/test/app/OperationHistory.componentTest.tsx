import * as Testing from "@solidjs/testing-library";
import * as Vitest from "vitest";
import { createSignal } from "solid-js";
import { OperationHistory, type OperationHistoryChange } from "../../src/app/OperationHistory";
import * as Journal from "../../src/domain/Journal";
import { care, operationsPage } from "./JournalTestSupport";

const recent = care({ id: "o4", date: "2026-04-04T00:00:00Z", moisture: "wet" });
const firstOlder = care({
  id: "o3",
  date: "2026-03-03T00:00:00Z",
  moisture: "moderatePlus",
});
const older = [
  firstOlder,
  care({ id: "o2", date: "2026-03-03T00:00:00Z", moisture: "dry", maybeNote: "Long note" }),
];

Vitest.describe("operation history", () => {
  Vitest.it("should disclose, page, edit, and collapse operation history", async () => {
    const windows: Journal.OperationWindow[] = [];
    const edited: Journal.Operation[] = [];
    const [operationChange, setOperationChange] = createSignal<OperationHistoryChange>();
    const getOperations = Vitest.vi.fn((window: Journal.OperationWindow) => {
      windows.push(window);
      return Promise.resolve(
        window.offset === 3
          ? operationsPage(older, true)
          : operationsPage(
              [care({ id: "o1", date: "2026-01-01T00:00:00Z", moisture: "wet" })],
              false,
            ),
      );
    });
    Testing.render(() => (
      <OperationHistory
        plantId={recent.plantId}
        substrateComponents={[]}
        pesticides={[]}
        getOperations={getOperations}
        operationChange={operationChange()}
        onEdit={(operation) => {
          edited.push(operation);
        }}
      />
    ));

    const show = Testing.screen.getByRole("button", { name: "Show operation history" });
    show.focus();
    Testing.fireEvent.click(show);

    const table = await Testing.screen.findByRole("table");
    Vitest.expect(Testing.within(table).getAllByRole("columnheader")).toHaveLength(5);
    Vitest.expect(Testing.within(table).getAllByText("03.03.2026")).toHaveLength(2);
    const firstDate = Testing.within(table).getAllByText("03.03.2026")[0];
    Vitest.expect(firstDate).toHaveAttribute("datetime", "2026-03-03T00:00:00Z");
    Vitest.expect(Testing.within(table).getByText("Long note")).toBeInTheDocument();
    Vitest.expect(windows).toEqual([{ offset: 3, size: 10 }]);
    const hide = Testing.screen.getByRole("button", { name: "Hide operation history" });
    await Testing.waitFor(() => Vitest.expect(hide).toHaveFocus());

    Testing.fireEvent.click(
      Testing.within(table).getByRole("button", {
        name: "Edit historical care operation 1 from 03.03.2026",
      }),
    );
    Vitest.expect(
      Testing.within(table).getByRole("button", {
        name: "Edit historical care operation 2 from 03.03.2026",
      }),
    ).toBeInTheDocument();
    Vitest.expect(edited).toEqual([firstOlder]);
    setOperationChange({
      kind: "edited",
      operation: {
        ...firstOlder,
        details: { ...firstOlder.details, maybeNote: Journal.note("Updated note") },
      },
    });
    Vitest.expect(await Testing.screen.findByText("Updated note")).toBeInTheDocument();

    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Next" }));
    const pageTwo = await Testing.screen.findByText("Page 2");
    await Testing.waitFor(() => {
      Vitest.expect(pageTwo).toHaveFocus();
    });
    Vitest.expect(windows).toEqual([
      { offset: 3, size: 10 },
      { offset: 13, size: 10 },
    ]);
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Previous" }));
    const pageOne = await Testing.screen.findByText("Page 1");
    await Testing.waitFor(() => {
      Vitest.expect(pageOne).toHaveFocus();
    });
    Vitest.expect(windows.at(-1)).toEqual({ offset: 3, size: 10 });

    setOperationChange({ kind: "logged" });
    await Testing.waitFor(() => {
      Vitest.expect(getOperations).toHaveBeenCalledTimes(4);
    });
    setOperationChange(undefined);

    hide.focus();
    Testing.fireEvent.click(hide);
    await Testing.waitFor(() =>
      Vitest.expect(
        Testing.screen.getByRole("button", { name: "Show operation history" }),
      ).toHaveFocus(),
    );
    Vitest.expect(Testing.screen.getByLabelText("Operation history")).toHaveAttribute(
      "aria-hidden",
      "true",
    );
    setOperationChange({ kind: "logged" });
    Vitest.expect(getOperations).toHaveBeenCalledTimes(4);
  });

  Vitest.it("should preserve disclosure focus while retrying a failed page", async () => {
    const getOperations = Vitest.vi
      .fn<(window: Journal.OperationWindow) => Promise<Journal.GetOperationsResult>>()
      .mockResolvedValueOnce({ kind: "readFailed", reason: new Error("private") })
      .mockResolvedValueOnce(operationsPage());
    Testing.render(() => (
      <OperationHistory
        plantId={recent.plantId}
        substrateComponents={[]}
        pesticides={[]}
        getOperations={getOperations}
        operationChange={undefined}
        onEdit={() => undefined}
      />
    ));

    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Show operation history" }));
    Vitest.expect(await Testing.screen.findByRole("alert")).toHaveTextContent(
      "Operation history could not be loaded.",
    );
    Vitest.expect(
      Testing.screen.getByRole("button", { name: "Hide operation history" }),
    ).toHaveFocus();
    Vitest.expect(Testing.screen.queryByText("private")).not.toBeInTheDocument();

    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Retry" }));
    Vitest.expect(await Testing.screen.findByText("No older operations.")).toBeInTheDocument();
    Vitest.expect(getOperations).toHaveBeenCalledTimes(2);
  });

  Vitest.it(
    "should preserve loading state when an edit arrives and the request rejects",
    async () => {
      let rejectRequest!: (reason?: unknown) => void;
      const pending = new Promise<Journal.GetOperationsResult>((_, reject) => {
        rejectRequest = reject;
      });
      const [operationChange, setOperationChange] = createSignal<OperationHistoryChange>();
      Testing.render(() => (
        <OperationHistory
          plantId={recent.plantId}
          substrateComponents={[]}
          pesticides={[]}
          getOperations={() => pending}
          operationChange={operationChange()}
          onEdit={() => undefined}
        />
      ));

      Testing.fireEvent.click(
        Testing.screen.getByRole("button", { name: "Show operation history" }),
      );
      Vitest.expect(
        await Testing.screen.findByText("Loading operation history…"),
      ).toBeInTheDocument();
      setOperationChange({ kind: "edited", operation: recent });
      Vitest.expect(Testing.screen.getByText("Loading operation history…")).toBeInTheDocument();

      rejectRequest(new Error("private"));
      Vitest.expect(await Testing.screen.findByRole("alert")).toHaveTextContent(
        "Operation history could not be loaded.",
      );
    },
  );

  Vitest.it("should ignore a superseded history response", async () => {
    let resolveFirst!: (result: Journal.GetOperationsResult) => void;
    const first = new Promise<Journal.GetOperationsResult>((resolve) => {
      resolveFirst = resolve;
    });
    const refreshed = care({
      id: "o3",
      date: "2026-03-03T00:00:00Z",
      moisture: "wet",
      maybeNote: "Refreshed",
    });
    const getOperations = Vitest.vi
      .fn<(window: Journal.OperationWindow) => Promise<Journal.GetOperationsResult>>()
      .mockReturnValueOnce(first)
      .mockResolvedValueOnce(operationsPage([refreshed]));
    const [operationChange, setOperationChange] = createSignal<OperationHistoryChange>();
    Testing.render(() => (
      <OperationHistory
        plantId={recent.plantId}
        substrateComponents={[]}
        pesticides={[]}
        getOperations={getOperations}
        operationChange={operationChange()}
        onEdit={() => undefined}
      />
    ));

    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Show operation history" }));
    setOperationChange({ kind: "logged" });
    Vitest.expect(await Testing.screen.findByText("Refreshed")).toBeInTheDocument();

    resolveFirst(operationsPage(older));
    await Promise.resolve();
    Vitest.expect(Testing.screen.getByText("Refreshed")).toBeInTheDocument();
    Vitest.expect(Testing.screen.queryByText("Long note")).not.toBeInTheDocument();
  });

  Vitest.it("should reset history when its index slot changes plant", async () => {
    const [plantId, setPlantId] = createSignal(Journal.plantId("p1"));
    const getOperations = Vitest.vi.fn(() => Promise.resolve(operationsPage(older)));
    Testing.render(() => (
      <OperationHistory
        plantId={plantId()}
        substrateComponents={[]}
        pesticides={[]}
        getOperations={getOperations}
        operationChange={undefined}
        onEdit={() => undefined}
      />
    ));

    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Show operation history" }));
    Vitest.expect(await Testing.screen.findByText("Long note")).toBeInTheDocument();

    setPlantId(Journal.plantId("p2"));

    Vitest.expect(Testing.screen.queryByRole("table")).not.toBeInTheDocument();
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Show operation history" }));
    await Testing.screen.findByRole("table");
    Vitest.expect(getOperations).toHaveBeenCalledTimes(2);
  });

  Vitest.it("should reload the current page after a deletion", async () => {
    const [operationChange, setOperationChange] = createSignal<OperationHistoryChange>();
    const getOperations = Vitest.vi.fn(() => Promise.resolve(operationsPage(older, true)));
    Testing.render(() => (
      <OperationHistory
        plantId={recent.plantId}
        substrateComponents={[]}
        pesticides={[]}
        getOperations={getOperations}
        operationChange={operationChange()}
        onEdit={() => undefined}
      />
    ));

    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Show operation history" }));
    await Testing.screen.findByRole("table");

    setOperationChange({ kind: "deleted" });
    await Testing.waitFor(() => {
      Vitest.expect(getOperations).toHaveBeenCalledTimes(2);
    });
  });

  Vitest.it("should focus a failed navigation and retry its requested page", async () => {
    const secondPage = care({
      id: "o1",
      date: "2026-01-01T00:00:00Z",
      moisture: "wet",
    });
    const getOperations = Vitest.vi
      .fn<(window: Journal.OperationWindow) => Promise<Journal.GetOperationsResult>>()
      .mockResolvedValueOnce(operationsPage(older, true))
      .mockResolvedValueOnce({ kind: "readFailed", reason: new Error("private") })
      .mockResolvedValueOnce(operationsPage([secondPage]));
    Testing.render(() => (
      <OperationHistory
        plantId={recent.plantId}
        substrateComponents={[]}
        pesticides={[]}
        getOperations={getOperations}
        operationChange={undefined}
        onEdit={() => undefined}
      />
    ));

    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Show operation history" }));
    await Testing.screen.findByRole("table");
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Next" }));
    const failure = await Testing.screen.findByRole("alert");
    await Testing.waitFor(() => {
      Vitest.expect(failure).toHaveFocus();
    });
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Retry" }));

    const pageTwo = await Testing.screen.findByText("Page 2");
    await Testing.waitFor(() => {
      Vitest.expect(pageTwo).toHaveFocus();
    });
    Vitest.expect(getOperations).toHaveBeenNthCalledWith(2, { offset: 13, size: 10 });
    Vitest.expect(getOperations).toHaveBeenNthCalledWith(3, { offset: 13, size: 10 });
  });
});
