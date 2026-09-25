# Archive substrate components and pesticides

> Standard: [Agentic Engineering Standards](https://github.com/Adobe-AIFoundations/agentic-workflow-standards) v1.2.0.
> Lifetime: open from creation through implementation, archived in the [sync docs & archive step](../../agentic-workflows.md#a-structured-development-skills).

**Date:** 2026-09-25

Let a household retire a substrate component or pesticide it no longer uses, guarded by the same irreversible-action confirmation used for archiving a plant.

## What & Why

- Substrate components and pesticides can be added and edited but never retired; a previously deferred plan for removal would delete the record outright and reject the attempt while it is still referenced, which was never built.
- Editing an existing substrate component or pesticide gains an adjacent, explicit archive action that permanently retires it after confirmation. A plant's current substrate mix and any logged operation's recorded pesticides keep referencing and displaying an archived entry unchanged, since removing the record itself would break those existing references; only new use is excluded.

## Domain / Design Notes

- A substrate component and a pesticide each gain the same active/archived status concept as a plant. Archiving distinguishes success, an unknown identity, an already-archived entry, and a failed write — mirroring the plant-archive and operation-delete result shapes:

  ```scala
  enum CatalogArchiveResult[+A]:
    case Archived(entry: A)
    case RecordMissing
    case AlreadyArchived
    case ArchiveFailed(reason: Throwable)

  trait SubstrateComponentCatalog:
    def archiveSubstrateComponent(id: SubstrateComponentId): CatalogArchiveResult[SubstrateComponent]

  trait PesticideCatalog:
    def archivePesticide(id: PesticideId): CatalogArchiveResult[Pesticide]
  ```

- Reading a catalog continues to return every entry regardless of status, so a plant's mix or an operation's pesticides can still resolve and display an archived entry's name and notes. Validating a new repot's substrate or a new care operation's pesticides accepts only active entries; an archived one is treated the same as an unknown one for that validation.
- An archived substrate component or pesticide is no longer editable, mirroring an archived plant.

## Alternatives Considered

- The deferred hard-delete plan (reject the delete while the entry is still referenced) was reconsidered: it cannot retire an entry a plant or operation already depends on without losing that history's meaning. Archiving retires the entry while keeping every existing reference intact.
- Leaving an archived entry editable was considered, but would let a correction retroactively change how a retired entry displays across every past reference; locking it, like an archived plant, avoids that.

## Acceptance Criteria

- Editing an active substrate component or pesticide shows an explicit archive action beside save, matching the placement used for an operation's delete action. Editing an already-archived one shows its archived status instead, with no save or archive action offered.
- Activating archive opens a keyboard-accessible confirmation with a prominent warning symbol and explicit text that archiving is permanent, matching the plant-archive and operation-delete confirmation: focus trap; cancelling or pressing Escape leaves the entry unchanged and restores focus; no restoration is offered.
- Confirming archive marks the entry archived and closes its editor; any usage site offering it for new selection stops listing it, while every plant and operation that already references it keeps displaying it unchanged.
- Saving a new repot's substrate or a new care operation's pesticides that references an archived entry, including a form opened before it was archived, fails without recording it.
- A stale or repeated archive confirmation on an already-archived entry, an unknown entry, or a write failure leaves recorded state unchanged and displays a meaningful error without reporting success.

## Doc Sync

- `GLOSSARY.md` — Nomenclature: a nomenclature may be archived, remaining referenced and displayed by existing plants and operations but excluded from new selection and further edits.
- `specs/design.md` — Domain model: substrate components and pesticides gain the same active/archived status as a plant; archiving excludes an entry from new repot and care-operation validation and locks it from further edits, while existing references keep resolving and displaying it unchanged.
- `specs/testing.md` — Strategy: extend the status-filtered-reads boundary to cover active-only catalog validation for new operations, distinct from the unfiltered reads that resolve existing references for display.

## Out of Scope

- Restoring an archived substrate component or pesticide.
- Hard-deleting a substrate component or pesticide outright.
