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
- Plant persistence accepts the new active plant independently of operation persistence. The initial substrate is its current mix until a later repot. Creation does not refresh watering attention; the garden presents a new plant missing from the previous measurement with unavailable cadence until the normal measurement includes it.

## Invariants

- A substrate is nonempty and contains distinct catalog components with positive shares totaling no more than 100%.
- When a plant has repots, the latest by recorded date and identifier continues to determine its current substrate.
- An archived plant cannot receive a new care or repot operation; existing operation history remains attached to its plant.

## Acceptance Criteria

- An "Add plant" button appears to the left of the garden/cemetery selector in the header. From either view it opens a labelled, keyboard-accessible side sheet with focus inside; cancelling or dismissing it returns focus to the button without creating a plant. The layout remains usable on narrow screens.
- The sheet accepts nonblank species and location, optional nickname, and a required valid substrate mix chosen from the component catalog. Invalid or missing details remain editable with accessible feedback; an unavailable catalog or an unknown component cannot be saved as a valid mix.
- Saving creates one active plant with the selected details and initial substrate and no operations. The journal shows it in the garden with the correct count and unavailable watering cadence even before the next scheduled measurement, including when creation starts from the cemetery, and moves focus to a persistent garden control. Operation logging remains a separate action; unrelated mismatched or duplicated attention remains a load failure.
- A failed creation retains the entered values and focus in the sheet with an accessible error for correction or retry. If reloading the garden fails after a successful save, the interface reports that the plant was saved without inviting a duplicate submission.

## Doc Sync

- `specs/design.md` — Domain model and Use cases and workflows: show plant creation without an operation, and show how the garden joins a newly created plant to a measurement that predates it.
