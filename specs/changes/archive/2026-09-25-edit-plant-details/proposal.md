# Edit an active plant's details

> Standard: [Agentic Engineering Standards](https://github.com/Adobe-AIFoundations/agentic-workflow-standards) v1.2.0.
> Lifetime: open from creation through implementation, archived in the [sync docs & archive step](../../agentic-workflows.md#a-structured-development-skills).

**Date:** 2026-09-25

Correct an active plant's species, nickname, location, and current substrate directly, without logging an operation.

## What & Why

- Today, an active plant's species, nickname, and location cannot be corrected after creation, and its current substrate can only be changed by logging a repot — which adds a dated event to the plant's care history even when nothing was actually repotted, such as when the plant's seeded starting details were wrong.
- Add an editor for an active plant's species, nickname, location, and substrate, alongside its existing archive action.

## Invariants

- A substrate is nonempty and contains distinct catalog components with positive shares totaling no more than 100%.
- When a plant has repots, the latest by recorded date and identifier continues to determine its current substrate.

## Tradeoffs Accepted

- A direct correction decouples the plant's current substrate from the dated repot history that otherwise ties every substrate change to a care event, so a correction made this way cannot be audited against a repot date. Accepted because an unaudited correction is less harmful to the record than a repot event that never happened.

## Acceptance Criteria

- An edit control appears beside the archive action for each active plant. It opens a labelled, keyboard-accessible side sheet, prefilled with the plant's current species, nickname, location, and substrate mix, with focus inside; cancelling or dismissing it returns focus to the edit control without changing the plant.
- The sheet enforces the same validation as plant creation: nonblank species and location, optional nickname, and a substrate mix chosen from the component catalog that stays nonempty, distinct, and within its share limit. Invalid or missing values remain editable with accessible feedback; an unavailable catalog or an unknown component cannot be saved.
- Saving revises the plant's stored species, nickname, location, and current substrate and creates, edits, or removes no operation; the plant's existing care and repot history is unchanged. The journal shows the revised details immediately and moves focus to a persistent control.
- A failed save retains the entered values and focus in the sheet with an accessible error for correction or retry. Attempting to save a correction for a plant no longer active reports a distinct failure instead of creating or resurrecting a plant.

## Doc Sync

- None. Editing patches a plant's existing details through domain components and endpoints `design.md` and `contracts.md` already document; no domain concept, contract, or workflow changes.
