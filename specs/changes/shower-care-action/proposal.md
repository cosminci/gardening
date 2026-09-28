# Shower care action; care-actions form layout

> Standard: [Agentic Engineering Standards](https://github.com/Adobe-AIFoundations/agentic-workflow-standards) v1.2.0.
> Lifetime: open from creation through implementation, archived per [the SDD skill's Archive PR phase](../../../.agents/skills/sdd/SKILL.md).

**Date:** 2026-09-29

**Grounded in:** Reading the current action-type set, its persistence, its metrics wiring, and its
two existing presentations (the entry form's checklist, and the read-only at-a-glance icon
rendering) directly — the new action slots into a closed set the same way each existing member
already does, with no new mechanism to design. The one real design fork — whether showering
should feed watering-cadence measurement the same way watering does — was resolved by the
maintainer rather than by exploration.

## What & Why

- Logging a care operation today offers four action types — watering, fertilizing, pesticide
  treatment, and pruning — with no way to record showering (rinsing a plant's foliage) as its own,
  distinct action. Add showering as a fifth action type alongside the existing four.
- On the operation-entry form, the moisture reading currently appears below the action selection;
  move it above, so the reading a user takes before acting is recorded before the actions taken in
  response to it.
- The action selection today seats two actions per row, collapsing to one per row at the narrowest
  widths, which stacks four actions across up to four rows. Seat three actions per row instead,
  uniformly across every viewport width and orientation, so five actions still fit two rows.

## Domain / Design Notes

The action-type set gains one more member with the same shape as its existing plain members (a
tag carrying no data of its own, unlike pesticide treatment's linked pesticide selection); a care
operation's action field remains a set of these values with no change to its shape.

## Alternatives Considered

Feeding showering into watering-cadence measurement, so it would reset/extend cadence the same way
a watering action does, was considered and rejected. A shower's purpose — rinsing foliage — doesn't
reliably mean the substrate was hydrated the way watering does, so folding it into the same
measurement would blur what the cadence indicator actually reports.

## Invariants

- Watering-cadence measurement continues to derive strictly from watering actions; no other action
  type, including showering, affects it.

## Tradeoffs Accepted

- A plant showered often but watered rarely still reads as due or overdue for watering. Accepted
  per the maintainer's explicit call: the cadence indicator should keep reporting
  substrate-watering only.

## Acceptance Criteria

- A care operation's action selection offers showering as a fifth option, alongside watering,
  fertilizing, pesticide treatment, and pruning, when creating or editing an operation; it is
  reachable and labelled as accessibly as the other four.
- Showering appears in the action-type summary of an operation's detailed read view, and in the
  at-a-glance icon rendering shown once the layout adapts to a narrow width, with its own distinct
  icon in the same color family as the watering icon.
- An operation recorded before this change, with no showering action, continues to display,
  summarize, and edit correctly.
- On the operation-entry form, in both the create and the edit case, the moisture reading appears
  before the action selection.
- The action selection seats three actions per row at any viewport width or orientation, mobile or
  desktop; five actions leave the second row's third slot empty, which is expected. The
  pesticide-selection list shown beneath the action selection (only when pesticide treatment is
  selected) keeps its own existing layout, unaffected by this change.

## Doc Sync

- `GLOSSARY.md` — Action-type: revise the illustrative list from "such as watering, fertilizing,
  pesticide treatment, or pruning" to also include showering.
