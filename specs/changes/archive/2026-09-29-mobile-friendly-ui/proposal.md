# Mobile-friendly layout and dialog behavior

> Standard: [Agentic Engineering Standards](https://github.com/Adobe-AIFoundations/agentic-workflow-standards) v1.2.0.
> Lifetime: open from creation through implementation, archived per [the SDD skill's Archive PR phase](../../../.agents/skills/sdd/SKILL.md).

**Date:** 2026-09-28

**Grounded in:** Testing the running app on a real phone, in both orientations, surfaced defects code review alone did not — width-only breakpoints that miss a landscape phone's actual width, and dialogs that broke specifically during their close transition. That is why this spec follows a spike rather than preceding it.

## What & Why

The app's layout and dialogs were designed for a desktop-sized screen only. This change makes the app mobile-friendly: touch-comfortable target sizing, a layout that fits within a phone's width in either orientation, an operation history that reads at a glance via icons instead of dense text, and dialogs that behave like bottom sheets on a phone instead of a fixed desktop side panel.

## Invariants

- Nothing in this change alters layout, sizing, or behavior for a session that is both non-touch and wide (desktop-scale) — a narrow non-touch window still gets the same width-driven layout changes a narrow touch screen does, since those are about available space, not input method.

## Acceptance Criteria

- On a touch device, in either orientation, every interactive control meets a comfortable minimum touch-target size, except where a control's own tight, space-constrained context leaves no room for it — that control instead stays pinned to a smaller, consistent size rather than overlapping neighboring content.
- The page header stays within the screen's width at any width down to a phone's narrowest common size, in both orientations, without ever forcing horizontal scrolling.
- On a touch device, a plant's photo/edit/archive actions stack vertically rather than horizontally in an upright layout; in a sideways layout with enough width for them, they stay horizontal.
- An operation's recorded actions and moisture reading render as icons rather than text once the layout has adapted for a narrow width, identically whether the operation is among the most recent or reached by expanding older history.
- An operation's date renders in a fixed-width short form at that same narrow width, and in its full descriptive form otherwise; its accessible label always states the full date regardless of which form is visually shown.
- A logged note renders without a "Note" label. On a narrow, upright layout it appears on its own line below an operation's icons; on a narrow, sideways layout it continues inline after them.
- Opening a dialog on a narrow, upright screen presents it anchored to the bottom, sized to its content and able to grow to the full screen height. The same dialog on a wide or sideways screen instead presents anchored to a screen edge, narrower than the available width.
- Opening a second dialog from within an already-open one leaves the first dialog's header visible and its content still fully rendered underneath. Closing the second dialog reveals the first dialog's real content, never an empty area.
- Content anchored to a screen edge leaves clear whatever inset a device's own hardware requires there (a rounded corner, a notch, a gesture-navigation strip), in both orientations.
- A touch-oriented selection list stays one item per row based on the space it is actually given, including inside a narrower dialog on a screen otherwise wide enough for two.

## Doc Sync

- `specs/design.md`, watering-attention bullet: the "each sheet sliding independently" sentence described only the wide-screen nested-dialog behavior; updated to also state the narrow-touch behavior (outer editor's header stays visible, content stays rendered underneath, instead of sliding away).

## Out of Scope

- A dedicated installable/offline (PWA) experience — this change is limited to how the existing page renders and behaves in an already-open mobile browser tab.
