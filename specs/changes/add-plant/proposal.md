# Add active plants to the journal

> Standard: [Agentic Engineering Standards](https://github.com/Adobe-AIFoundations/agentic-workflow-standards) v1.2.0.
> Lifetime: open from creation through implementation, archived in the [sync docs & archive step](../../agentic-workflows.md#a-structured-development-skills).

**Date:** 2026-09-24

Add active plants directly from the journal.

## What & Why

- The journal can display and archive plants but cannot add one. Creation should be available beside the garden/cemetery selector in a side sheet, from either view.
- A new plant starts active with its own initial substrate and an empty care history. Care and repot operations are logged separately, when they happen.

## Domain / Design Notes

- The journal creates an active plant from species, optional nickname, location, and an initial substrate; it assigns the plant identity and records it independently of operations. Creation distinguishes an unknown substrate component, a failed catalog read, and a failed write from a created plant.
- Plant persistence accepts the new active plant independently of operation persistence. The initial substrate is its current mix until a later repot; after creation, attention refresh includes the plant with insufficient watering history. If that refresh fails after the plant is saved, the saved outcome remains distinguishable from a failed creation, and an unmatched attention view is not presented as current.

## Invariants

- A substrate is nonempty and contains distinct catalog components with positive shares totaling no more than 100%.
- When a plant has repots, the latest by recorded date and identifier continues to determine its current substrate.
- An archived plant cannot receive a new care or repot operation; existing operation history remains attached to its plant.

## Tradeoffs Accepted

- Creation incurs the cost and latency of a full attention refresh, acceptable for a small household garden so that a newly created plant can appear in the current garden view.

## Acceptance Criteria

- An "Add plant" button appears to the left of the garden/cemetery selector in the header. From either view it opens a labelled, keyboard-accessible side sheet with focus inside; cancelling or dismissing it returns focus to the button without creating a plant. The layout remains usable on narrow screens.
- The sheet accepts nonblank species and location, optional nickname, and a required valid substrate mix chosen from the component catalog. Invalid or missing details remain editable with accessible feedback; an unavailable catalog or an unknown component cannot be saved as a valid mix.
- Saving creates one active plant with the selected details and initial substrate, no operations, and unavailable watering cadence. The journal shows it in the garden with the correct count, including when creation starts from the cemetery, and moves focus to a persistent garden control. Operation logging remains a separate action.
- A failed creation retains the entered values and focus in the sheet with an accessible error for correction or retry. If attention or garden refresh fails after a successful save, the interface reports that the plant was saved, does not invite a duplicate submission, and shows a load failure rather than a mismatched garden.

## Doc Sync

- `specs/design.md` — Domain model, Processing rules, and Edge cases for creating an active plant with an initial substrate and no history.
- `specs/contracts.md` — HTTP API and Error responses for plant creation.
- `specs/testing.md` — Service-specific strategy, Fixtures & data setup, and Integration boundaries for plant creation and initial attention.
- `specs/operational.md` — Scaling characteristics of refreshing attention on creation.
