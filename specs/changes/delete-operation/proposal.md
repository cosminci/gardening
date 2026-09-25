# Delete a logged operation

> Standard: [Agentic Engineering Standards](https://github.com/Adobe-AIFoundations/agentic-workflow-standards) v1.2.0.
> Lifetime: archived after implementation and living-doc sync.

**Date:** 2026-09-25

Let a household permanently remove a mistakenly logged operation from its editor, guarded by the same irreversible-action confirmation used for archiving a plant.

## What & Why

- An operation, once logged, can currently only be edited — its kind-specific details corrected — never fully discarded, even when it was logged in error (wrong plant, duplicate entry, a slip while testing).
- Editing an operation gains an adjacent, explicit delete action that permanently removes it from the plant's history after confirmation.

## Domain / Design Notes

- The journal accepts a delete request for an operation identity and distinguishes success, an unknown operation, and a failed write — mirroring the existing archive-plant result shape.
- The journal rejects deleting a plant's current latest repot operation with its own distinct outcome, since the plant's current substrate is derived from that operation and nothing recomputes it on removal; deleting any other repot or any care operation is unrestricted.
- Deletion is a hard removal: the operation stops appearing in any read (recent and paginated history, recorded care range) as though it had never been logged. It changes nothing else about the plant or its remaining operations, and applies the same way whether the plant is active or archived.

## Alternatives Considered

- Allowing deletion of a plant's current latest repot operation was considered. Recomputing its substrate from the next-latest repot afterward would add compensation complexity for a rare correction; leaving the snapshot stale instead would silently misrepresent the plant's current mix. Rejecting the deletion avoids both and keeps the current-substrate invariant simple.

## Invariants

- An operation's recorded date, kind, and plant do not change under editing.
- The plant's current substrate remains determined by its latest remaining repot operation.
- Watering attention and the cemetery's recorded care range are computed from whichever operations remain at the time they are computed.

## Acceptance Criteria

- Opening an existing operation for editing shows an explicit delete action next to save; a newly-logged, not-yet-saved operation offers no delete action.
- Activating delete opens a keyboard-accessible confirmation with a prominent warning symbol and explicit text that deletion is permanent, matching the archive-plant confirmation's interaction: focus trap, cancel or Escape leaves the operation unchanged and restores focus, and no restoration is offered.
- Confirming delete on a care operation or a non-latest repot removes it, closes the editor, and refreshes the plant's operation history.
- Confirming delete on a plant's current latest repot operation is rejected with a distinct, meaningful error; the operation and the plant's current substrate remain unchanged.
- A stale or repeated confirmation on an already-deleted operation, or a write failure during deletion, leaves recorded state unchanged and displays a meaningful error without reporting success.

## Doc Sync

- `specs/design.md` — Domain model: add that a logged operation may be permanently deleted, except a plant's current latest repot, which must remain so the plant's current substrate stays meaningful.

## Out of Scope

- Restoring a deleted operation.
- Deleting multiple operations at once.
