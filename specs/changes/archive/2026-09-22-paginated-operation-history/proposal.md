# Paginated operation history

> Standard: Agentic Engineering Standards v1.2.0
>
> Lifetime: archived after implementation and living-doc sync.

**Date:** 2026-09-22

## What

Access to a plant's operations older than the three shown on its card.

- A shallow control sits directly below the three recent-operation columns, spanning their combined width, shown only when older operations exist. Its icon is three horizontal strokes of diminishing length.
- Activating it expands an operation-history section below the card row with a deliberate animation; the same control, now at the bottom of the section, collapses it upward.
- The expanded section lists operations older than the three recent ones, newest first, ten per page, one row per operation, in a table-like layout spanning roughly 70% of the row's width, with Date, Type, Details, and Notes columns. Rows and cells may wrap onto multiple lines, reusing the icons, labels, and formatting of the recent-operation cells.
- Every row opens the existing operation-edit side sheet — there is no second editing path.

## Domain

Retrofit the existing operation read for bounded windows:

```scala
opaque type OperationOffset = Int   // >= 0
opaque type OperationPageSize = Int // 1..10
final case class OperationWindow(offset: OperationOffset, size: OperationPageSize)
final case class OperationPage(operations: Vector[Operation], hasNextPage: Boolean)

trait PlantJournal:
  def getOperations(plantId: PlantId, window: OperationWindow): GetOperationsResult

trait PlantJournalStore:
  def getOperations(plantId: PlantId, window: OperationWindow): GetOperationsResult
```

- Order: date descending, then operation id descending.
- Recent operations: offset 0, size 3.
- First history page: offset 3, size 10.
- History page N: offset `3 + (N - 1) * 10`.
- `hasNextPage` marks the last page.
- The existing HTTP endpoint accepts the window.
- The existing frontend client method accepts the window.

## Acceptance criteria

- Collapsing hides the section; the control's icon and position are identical collapsed and expanded.
- Expand/collapse animates; with `prefers-reduced-motion`, the section shows/hides instantly.
- The control and pagination are keyboard-operable, expose `aria-expanded`, and have an accessible name distinguishing "show" from "hide" operation history.
- A loading state is shown while a page is fetched; a failed fetch shows a retry option without collapsing the section; an empty page (no older operations) shows a plain empty state.
- Editing refreshes the visible row through the existing side sheet.
- Editing cannot change ordering because timestamps are immutable.
- Long details or notes wrap within their cell rather than being clipped; the table remains usable at narrow widths without breaking row/column association.

## Tradeoffs

- History size (10) is fixed.
- Offset pagination keeps the contract small.

## Out of scope

- Filtering or searching within operation history.

## Doc Sync

- specs/design.md — bounded operation windows and history presentation.
- specs/contracts.md — offset and page-size parameters on the operations endpoint.
- specs/testing.md — pagination boundary and reduced-motion test coverage.
