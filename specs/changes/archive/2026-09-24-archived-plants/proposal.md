# Garden and cemetery views for archived plants

> Standard: [Agentic Engineering Standards](https://github.com/Adobe-AIFoundations/agentic-workflow-standards) v1.2.0 (accessed 2026-09-23).
> Lifetime: archived after implementation and living-doc sync.

**Date:** 2026-09-23

Browse archived plants alongside the garden and permanently retire plants with an explicit confirmation.

## What & Why

- The journal currently displays only active plants, and its header shows only their count; archived plants can be retrieved but have no browser view or user-facing archive action.
- A prominent garden/cemetery selector will show both populations and let the household browse archived plants with their care history.
- A plant can be archived from its garden card after a confirmation that clearly states the change cannot be undone.

## Domain / Design Notes

- The journal accepts an archive request for a plant identity and distinguishes success, an unknown plant, an already archived plant, and a failed write. The only permitted status transition is active to archived; the plant and its operations remain recorded.
- The cemetery's recorded date range comes from the earliest and latest dated operations for each plant, across its entire history. Existing operation details remain editable, but logging a new operation against an archived plant is rejected without changing its history.
- The journal provides an archived-plant count independently of the archived-plant list, so the garden can show the cemetery count without loading its cards.
- Watering attention remains an active-garden measurement. The garden view uses current plant details and attention without treating a recently archived plant in a lagging measurement as an inconsistent live plant.

## Alternatives Considered

- Allowing restoration would soften accidental archives, but conflicts with the chosen permanent retirement; require an explicit warning and cancellation instead.
- Saving an archive date would report the transition precisely, but the cemetery is intended to show the span of recorded care rather than when the archive action happened.

## Invariants

- Existing operations remain attached to their plant; users cannot delete their history.
- An operation's recorded date and kind remain immutable when its details are edited.
- The latest repot continues to determine the plant's current substrate.
- Active cards retain their attention ordering, bounded recent and historical operation pages, and explicit failure on genuinely mismatched or duplicated attention data.

## Tradeoffs Accepted

- The last recorded operation is only an approximation of the end of a plant's life; using recorded care dates avoids presenting the archive action as a death date.

## Acceptance Criteria

- The header displays "Plant Journal" without a subtitle and presents a prominent, responsive two-way control with a leafy tree or flower for the garden and a cemetery cross for the cemetery, both counts, visible selected state, and accessible names independent of the icons. It opens in the garden and supports keyboard operation. The garden count equals its loaded list size; the cemetery count comes from a count-only read until its list is opened on demand, after which the count equals the loaded list size. Empty lists show zero; a failed count read shows failure rather than a guessed number.
- The cemetery presents archived cards with the same plant details, recent and paginated operation history, and editing as the garden, but no add-operation action. Its first column shows a labelled RIP gravestone in place of watering attention, with the first and last recorded operation dates in `dd.mm.yyyy` format and no time; a single operation yields the same start and end date, and a plant without operations clearly shows that its dates are unknown.
- An archive control appears at the upper right of each active plant summary. Activating it opens a keyboard-accessible confirmation with a prominent warning symbol and explicit text that archiving is permanent; cancelling leaves the plant unchanged and restores focus. No restoration control is offered.
- Confirming archive moves only that plant from garden to cemetery, refreshes the active list and archived count from the journal, preserves its history, and restores focus to a persistent control. Any later visit to the cemetery loads its current list, including that plant. A repeated or stale archive attempt, an unknown plant, or a write failure leaves recorded state unchanged and displays a meaningful error without reporting success.
- Attempts to log a new care or repot operation for an archived plant, including a form opened before archiving, fail without recording it; existing operation edits retain their rules and never change the displayed date range.
- Archiving and subsequent browsing remain usable while the active-only watering measurement catches up; unrelated attention inconsistencies and failed reads remain visible as failures, not partial success.

## Doc Sync

- `GLOSSARY.md` — Plant status and the cemetery's recorded date range.
- `specs/design.md` — Domain model, Processing rules, Edge cases, and Invariants for archiving and both views.
- `specs/contracts.md` — HTTP API and Error responses for the archive transition, archived count read, and rejected operation logging.
- `specs/testing.md` — Service-specific strategy, Fixtures & data setup, and Integration boundaries for status transitions and archived browsing.
- `specs/operational.md` — Scaling characteristics of on-demand cemetery loading, count-only reads, and archived date ranges.
