# Shower care action; care-actions form layout

> Standard: [Agentic Engineering Standards](https://github.com/Adobe-AIFoundations/agentic-workflow-standards) v1.2.0.
> Lifetime: open from creation through implementation, archived per [the SDD skill's Archive PR phase](../../../.agents/skills/sdd/SKILL.md).

**Date:** 2026-09-29

**Grounded in:** Already understood directly from the existing action-type set, its two
presentations, and one maintainer decision (below) — no unknowns to spike.

Add showering as a fifth care action type; reflow the care-actions form.

## What & Why

Care operations support four action types (watering, fertilizing, pesticide treatment, pruning).
Add showering as a fifth. On the operation form, show the moisture reading above the action
selection (currently below), and seat three actions per row at any width (currently two, collapsing
to one on narrow screens).

## Domain / Design Notes

Showering is a plain member of the action-type set, like fertilizing or pruning: no attached data.

## Invariants

- Watering-cadence measurement derives from watering actions only; showering doesn't affect it
  (maintainer decision — a shower doesn't necessarily hydrate the substrate).

## Acceptance Criteria

- Showering is selectable alongside the other four actions when creating/editing an operation, and
  appears in the action summary and icon rendering wherever the other four do, with its own icon in
  watering's color family.
- An existing operation with no showering action still displays and edits correctly.
- The moisture reading appears above the action selection on the operation form.
- The action selection seats three per row at any width/orientation; the pesticide list beneath it
  is unaffected.

## Doc Sync

- `GLOSSARY.md` — Action-type: add showering to the illustrative list.
