# Shower care action; care-actions form layout

> Standard: [Agentic Engineering Standards](https://github.com/Adobe-AIFoundations/agentic-workflow-standards) v1.2.0.
> Lifetime: open from creation through implementation, archived per [the SDD skill's Archive PR phase](../../../.agents/skills/sdd/SKILL.md).

**Date:** 2026-09-29

**Grounded in:** Already understood directly from the existing action-type set, its two presentations, and one maintainer decision (below) — no unknowns to spike.

Add showering as a fifth care action type; reflow the care-actions form.

## What & Why

Care operations support four action types (watering, fertilizing, pesticide treatment, pruning). Add showering as a fifth. On the operation form, show the moisture reading above the action selection (currently below), and seat three actions per row at any width (currently two, collapsing to one on narrow screens).

## Domain / Design Notes

`ActionType` gains one plain member, `Showered`, alongside the existing members — no attached data, same as `Fertilized`/`Pruned`:

```scala
enum ActionType(val label: String):
  case Watered    extends ActionType("Watered")
  case Showered   extends ActionType("Showered")
  case Fertilized extends ActionType("Fertilized")
  case Pesticide  extends ActionType("Insecticide / H2O2")
  case Pruned     extends ActionType("Pruned")
  case NoAction   extends ActionType("None")
```

No persistence schema change: `operation.payload` is a JSON blob checked only for `json_valid`, not an enumerated `check (... in (...))` column (unlike `pesticide.type`), so a new `ActionType` name is valid on day one — no Flyway migration.

## Acceptance Criteria

- Showering is selectable alongside the other four actions when creating/editing an operation, and appears in the action summary and icon rendering wherever the other four do, with its own icon in watering's color family.
- Showering does not affect watering-cadence measurement, which continues to derive from watering actions only.
- An existing operation with no showering action still displays and edits correctly.
- The moisture reading appears above the action selection on the operation form.
- The action selection seats three per row at any width/orientation; the pesticide list beneath it is unaffected.

## Doc Sync

- `GLOSSARY.md` — Action-type: add showering to the illustrative list.
