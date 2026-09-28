# Mobile-friendly layout and dialog behavior

> Standard: [Agentic Engineering Standards](https://github.com/Adobe-AIFoundations/agentic-workflow-standards) v1.2.0.
> Lifetime: open from creation through implementation, archived per [the SDD skill's Archive PR phase](../../../.agents/skills/sdd/SKILL.md).

**Date:** 2026-09-28

**Grounded in:** Iterative testing against the running app from a real phone on the same network, across both orientations and against real plant data, found concrete, non-obvious defects no amount of code reading surfaced up front — most persistently, that width-only responsive conditions miss a phone held in landscape (its width regularly exceeds narrow breakpoints tuned for portrait), and that a dialog's own close-time animation state is not the same state its layout rules were keyed to, which silently outweighed every subsequent rule written to fix it. Both patterns recurred across unrelated parts of this change and are the concrete reason a spec could not have been written first.

Make every page surface — chrome, cards, operation history, and every dialog — respond to touch capability, orientation, and available width independently, so the app is as usable on a phone as it already is on a laptop.

## What & Why

Today, layout and interaction across the page's chrome, a plant's card actions, its operation history, and every modal dialog were designed and tuned for a laptop-sized screen; the few conditions that varied anything reacted to viewport width alone. On a phone this produced header and list content wider than the screen, action icons and touch targets sized for a mouse, an operation history that read poorly at a glance, and dialogs that behaved like a fixed-width desktop side panel regardless of orientation or how much space was actually available.

Now, each surface adapts along whichever condition actually governs it — touch capability, current orientation, or available width — independently of the others, rather than lumping every "mobile" case into one check.

## Alternatives Considered

- Hiding an already-open dialog entirely while a dialog opened from within it is on screen (matching this app's original mobile behavior before this change): rejected — testing showed this produces a visible flash of the page behind the dialog stack while closing, and drops the first dialog's own identity (its title) from view while a second one is open.
- A single touch-input condition standing in for every mobile-specific rule, including ones that are really about available width: rejected — a phone held sideways reports a width many "narrow-screen" rules don't catch, reproducing the same overflow and mis-sizing this change fixes elsewhere; touch capability and available width vary independently and are treated as independent conditions throughout.
- Reacting to a surface's own rendered width rather than the screen's, everywhere a width condition exists: rejected beyond the one selection list that actually needs it — every other affected surface renders in exactly one place today, where its own width and the screen's width always move together, so there is no independent case for the rest to get wrong.

## Invariants

- Nothing in this change alters layout, sizing, or behavior for a non-touch or wide (desktop-scale) session.

## Acceptance Criteria

- On a touch device, in either orientation, every interactive control meets a comfortable minimum touch-target size.
- The page header stays within the screen's width at any width down to a phone's narrowest common size, in both orientations, without ever forcing horizontal scrolling.
- On a touch device, a plant's photo/edit/archive actions stack vertically rather than horizontally, in both orientations.
- An operation's recorded actions, moisture reading, and kind render as icons rather than text once the layout has adapted for a narrow width, identically whether the operation is among the most recent or reached by expanding older history.
- An operation's date renders in a fixed-width short form at that same narrow width, and in its full descriptive form otherwise; its accessible label always states the full date regardless of which form is visually shown.
- A logged note renders without a "Note" label. On a narrow, upright layout it appears on its own line below an operation's icons; on a narrow, sideways layout it continues inline after them.
- Opening a dialog on a narrow, upright screen presents it anchored to the bottom, sized to its content and able to grow to the full screen height. The same dialog on a wide or sideways screen instead presents anchored to a screen edge, narrower than the available width.
- Opening a second dialog from within an already-open one leaves the first dialog's header visible and its content still fully rendered underneath. Closing the second dialog reveals the first dialog's real content, never an empty area.
- Content anchored to a screen edge leaves clear whatever inset a device's own hardware requires there (a rounded corner, a notch, a gesture-navigation strip), in both orientations.
- A touch-oriented selection list stays one item per row based on the space it is actually given, including inside a narrower dialog on a screen otherwise wide enough for two.

## Doc Sync

- None. This changes only presentation-layer rendering and interaction; no living doc in this repo records frontend UI/UX conventions, and no domain, contract, or architecture fact changes.

## Out of Scope

- A dedicated installable/offline (PWA) experience — this change is limited to how the existing page renders and behaves in an already-open mobile browser tab.
