# Resource-oriented plant care boundaries

> Standard: [Agentic Engineering Standards](https://github.com/Adobe-AIFoundations/agentic-workflow-standards) v1.2.0.
> Lifetime: open from creation through implementation, archived in the [SDD archive step](../../../.agents/skills/sdd/SKILL.md).

**Date:** 2026-09-24

Give plants, operations, attention, substrate components, and pesticides independent resource and domain boundaries without changing what the household can record.

## What & Why

- A single journal API and service currently own plant records, care operations, and both editable catalogs; one persistence adapter also owns attention samples. Existing HTTP paths distinguish most resources, but their behavior and dependencies are still combined.
- Give each resource its own REST-oriented API and domain capability. Plants and their operations remain the journal; watering attention is a separate read-only projection, and substrate components and pesticides are independent catalogs.
- This change builds on the approved archived-plants behavior; its implementation must merge before this change is implemented. Until then the archive transition and cemetery are planned behavior, not part of this branch's running baseline.

## Domain / Design Notes

- Plant behavior owns status-filtered reads, archived counts, and the active-to-archived transition. Operation behavior owns bounded history, date ranges, logging, editing, catalog-reference validation, and latest-repot synchronization with the plant's current substrate. These behaviors share the coordination needed to serialize archival and operation writes and to compensate a failed repot update.
- Attention retains its independently refreshed, active-plant-only measurement and read capability. Substrate-component and pesticide services each own their own catalog reads, creation, and editing; plant and operation behavior depend on the relevant catalog capabilities rather than a combined journal store.
- Journal persistence owns the cooperating plant and operation records; attention samples have a read capability. Each catalog owns its own persistence capability and stored representation, independent of journal and attention persistence.
- Browser-facing capabilities follow the same resource boundaries while the garden composes plant, operation, attention, and catalog results. Resource identifiers, domain values, and the generated HTTP contract remain the common vocabulary across boundaries.
- HTTP exposes resource reads and supported mutations with resource-oriented semantics; archiving changes the plant resource rather than invoking an action endpoint. Substrate components occupy a substrate resource group distinct from the pesticide catalog. The attention projection has no mutation capability.

## Invariants

- An operation remains attached to its plant; its identifier, date, plant, and care-or-repot kind remain immutable on edit. User-facing deletion remains unavailable.
- A successful latest repot determines the plant's current substrate; an older repot or ordinary care does not. Failed post-write synchronization compensates the operation write and retains both causes if compensation fails.
- Catalog identifiers remain stable across edits, and care and repot operations may reference only existing pesticides and substrate components respectively.
- Active-plant attention remains a complete measurement independent of current plant details; an unsuccessful refresh retains the last complete projection.

## Tradeoffs Accepted

- The archive action and substrate-component HTTP shapes change to resource-grouped forms; the bundled browser client and generated contract move together rather than retaining parallel routes.
- Separate resource boundaries add composition at the garden and at the plant/operation consistency seam, in exchange for independent catalog and attention evolution.

## Acceptance Criteria

- Each resource has an independently exercised API and domain capability: plants expose reads and archiving, operations expose history and writes, attention exposes the current projection, and each catalog exposes reads and writes. The substrate group addresses components separately from plant and repot mixes; no deletion or saved mix templates are introduced.
- The garden and, after its prerequisite merges, cemetery retain their supported loading, ordering, pagination, inline catalog editing, date presentation, and keyboard/focus behavior while consuming the separate capabilities. A failed catalog request reports its own failure without silently changing plant or operation records; failed or mismatched plant and attention reads do not present an incomplete view as current.
- Existing input and record outcomes remain observable across resource boundaries: invalid status, date, operation window, or catalog input is rejected; an invalid catalog identifier and a missing catalog entry remain distinct; missing operations and kind conflicts remain distinct. Unknown or archived plants reject new operations, while unknown or already archived plants and failed archive writes leave the plant unchanged with a visible failure after the prerequisite merges. Invalid catalog references fail the operation without a write; existing error classifications remain unchanged.
- The generated HTTP contract and bundled client agree on resource representations and failure responses. After the prerequisite merges, archive changes plant state as a resource mutation; the action-style archive route is replaced. The substrate-component catalog is reachable through its grouped resource contract rather than a parallel legacy route.

## Doc Sync

- `specs/design.md` — Service overview and Component architecture for resource boundaries; Processing rules for cross-resource repot, archive, attention, and catalog workflows.
- `specs/contracts.md` — HTTP API, Error responses, and Versioning & compatibility for the grouped resources and plant archive transition.
- `specs/testing.md` — Service-specific strategy and Integration boundaries for independent domain capabilities, HTTP resource seams, and catalog persistence seams.

## Out of Scope

- Saved substrate-mix favorites or aliases.
- Hypermedia discoverability links.
