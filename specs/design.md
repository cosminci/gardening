# plant-journal design

> Standard: Agentic Engineering Standards v1.2.0

## Service overview

plant-journal keeps the household's plants, current substrates, and dated care history. A direct-style Scala backend owns the journal rules, persists them in SQLite, and materializes watering attention; a SolidJS browser orders active plants by that attention, shows recent and paginated historical operations, and provides operation logging and editing.

## Domain model

A Plant has fixed descriptive details, an active or archived status, and a current Substrate. Each Operation belongs to one Plant and is either a Care operation or a Repot operation. Plant attention pairs an active Plant with unavailable or inferred watering cadence measured from its latest bounded watering sample. Inferred cadence carries exact urgency and a current, overdue, or red-alert state. Substrate-components and Pesticides are editable nomenclatures with stable identifiers, names, and optional usage information; each Pesticide also has a Fungicide, Insecticide, or Treatment type. A Substrate records component identifiers and percentage shares, while a Care operation records selected pesticide identifiers. Moisture-levels, Action-types, and Pesticide types are fixed English vocabularies; free text remains verbatim.

```mermaid
erDiagram
    PLANT ||--o{ OPERATION : records
    PLANT ||--|| SUBSTRATE : "currently uses"
    OPERATION ||--o| CARE : "is"
    OPERATION ||--o| REPOT : "is"
    REPOT ||--|| SUBSTRATE : "establishes"
    SUBSTRATE ||--|{ SUBSTRATE_PART : contains
    SUBSTRATE_PART }o--|| SUBSTRATE_COMPONENT : selects
    CARE }o--o{ ACTION_TYPE : records
    CARE }o--|| MOISTURE_LEVEL : observes
    CARE }o--o{ PESTICIDE : applies
```

## Processing rules

- Journal reads return active plants and bounded windows of a requested plant's operations. Operation pages are newest-first, with identifiers breaking timestamp ties. Every persisted row is validated before plant-status filtering, so malformed archived rows cannot disappear silently.
- Logging assigns the operation identifier and timestamp in the backend. Care logging changes only the journal; repot logging also changes the plant's current substrate.
- A repot log succeeds only after both the operation and current substrate are persisted. If the substrate update or its prerequisite read fails, the new operation is removed; a failed compensation is reported with the original failure.
- Editing may change only kind-specific operation details. Editing the latest repot also updates current substrate; editing an older repot does not. A failed latest-repot substrate update restores the previous operation details.
- Log and edit workflows are serialized so their synchronization and compensation steps cannot interleave.
- Operations cannot be deleted by users because they record care that already happened.
- Substrate-component and pesticide catalogs can be listed, extended, and edited, but not deleted. Editing preserves the stable identifier used by existing substrates and operations.
- The browser loads both catalogs for operation forms. Substrate-components are defined or edited beside a substrate mix; pesticides are defined or edited beside the pesticide choices. Each editor opens in an adjacent sheet without replacing the operation form.
- The browser orders plants for display and shows each plant's three latest operations from oldest to newest. Older operations load on demand in ten-row, newest-first pages; loading, empty, and retryable failure states remain inside the expanded history.
- Plant attention is materialized at startup and refreshed every five minutes. Each measurement reads at most the latest 20 watering timestamps per active Plant; fewer than five makes cadence unavailable, otherwise cadence is the arithmetic mean of consecutive timestamps.
- Urgency is the exact elapsed/cadence ratio. A Plant is current through its cadence, overdue immediately afterward, and in red alert at cadence plus 24 hours. A zero cadence has zero urgency at zero elapsed and unbounded urgency after time advances.
- The browser orders unavailable cadence first, then inferred cadence by descending urgency, then location, species, nickname, and Plant identifier. A card whose index slot changes Plant resets its local operation-history state.
- Recent and historical operations share display semantics and the same editing sheet while retaining layouts suited to cards and rows. Long values wrap, note line breaks remain visible, narrow tables scroll without losing column association, and reduced-motion preferences suppress expansion animation.
- After a successful log, the browser reloads the journal. A historical edit refreshes its visible row without creating a separate editing path.

## Edge cases

- An empty journal and an operation-list read for an unknown plant both return an empty collection.
- A missing plant or operation in a single-record workflow is distinct from an empty collection.
- Editing an operation as the other operation kind is rejected without changing the journal.
- Editing a missing nomenclature is distinct from a catalog-access failure.
- Independently malformed persisted rows are all reported together and attributed to their plant or operation identifiers.
- Database-access failures are reported separately from stored-data corruption.
- API failures remain explicit failures in the browser; the interface does not present stale writes as successful.
- A startup attention-read failure prevents startup. A later refresh failure retains the last complete projection and does not stop HTTP service.
- Plants without waterings remain visible with unavailable cadence. Equal watering timestamps are ordered by Operation identifier. Unknown attention values fail at the HTTP client boundary rather than rendering a reassuring default.

## Invariants

- Every Substrate is non-empty, contains each Substrate-component at most once, assigns each part a share from 1 through 100, and has a total share no greater than 100.
- A Care operation carries no Substrate; a Repot operation always carries one.
- An Operation's identifier, Plant, timestamp, and care-or-repot kind never change after logging.
- Every operation page is ordered by timestamp descending, then identifier descending.
- When a Plant has repot operations, its current Substrate matches its latest repot after every successful log or edit.
- Nomenclature identifiers remain stable when their editable name, information, or pesticide type changes.
- Every Pesticide has exactly one supported Pesticide type.
- Every attention sample count is between 0 and 20, and watering samples are newest-first with Operation identifiers breaking timestamp ties.
- Controlled-vocabulary values are English; nomenclature text and other free text are preserved verbatim.
- Domain behavior depends on injected capabilities and never on HTTP, SQLite, clocks, or identifier implementations directly.

## Component architecture

The domain defines journal behavior, the materialized attention monitor, and use-case-shaped capability ports. The application wires direct-style adapters into those capabilities; adapters translate HTTP, persistence, clock, and identifier concerns without owning business orchestration. The SQLite plant adapter implements both persistence ports because they share the same schema, transactor, and validated plant and timestamp decoding.

```mermaid
flowchart LR
    Browser["SolidJS browser"] -->|"same-origin HTTP"| HTTP["Tapir / Netty HTTP adapter"]
    Static["Built frontend assets"] --> HTTP
    HTTP --> Journal["PlantJournal domain service"]
    HTTP --> Attention["PlantAttentionMonitor"]
    Journal --> StorePort["PlantJournalStore capability"]
    Attention --> AttentionPort["PlantAttentionStore capability"]
    Journal --> Clock["Clock capability"]
    Journal --> IDs["IdGenerator capability"]
    StorePort --> SQLite["SQLite adapter"]
    AttentionPort --> SQLite
```
