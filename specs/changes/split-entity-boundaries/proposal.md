# Resource-oriented plant care boundaries

> Standard: [Agentic Engineering Standards](https://github.com/Adobe-AIFoundations/agentic-workflow-standards) v1.2.0.
> Lifetime: open from creation through implementation, archived in the [SDD archive step](../../../.agents/skills/sdd/SKILL.md).

**Date:** 2026-09-24

Group the existing HTTP operations by resource; the [route contract](contracts.md) lists every old and proposed path.

## What & Why

The HTTP API, journal service, browser client, and persistence adapter currently group plant/operation behavior with two unrelated catalogs. Split HTTP routes by resource, while plants and operations remain one journal domain and persistence capability. Extract only substrate-component and pesticide catalog responsibilities; attention retains its separate capability. No HTTP operation, verb, payload, result type, or failure classification changes. The approved archived-plants implementation must merge before this change is implemented.

## Domain / Design Notes

The journal and catalog methods retain their signatures. Attention uses its existing full refresh instead of a per-plant cache mutation:

```scala
trait PlantJournal:
  def getPlants(status: PlantStatus): GetPlantsResult
  def getArchivedCount: ArchivedCountResult
  def archivePlant(id: PlantId): ArchivePlantResult
  def getOperations(plantId: PlantId, window: OperationWindow): GetOperationsResult
  def getOperationDateRange(plantId: PlantId): GetOperationDateRangeResult
  def logOperation(plantId: PlantId, date: Instant, op: OperationDetails): LogOperationResult
  def editOperation(id: OperationId, details: OperationDetails): EditOperationResult

trait PlantAttentionMonitor:
  def current: AttentionProjection
  def refreshAll: RefreshAttentionResult

trait SubstrateComponentCatalog:
  def getSubstrateComponents: CatalogReadResult[SubstrateComponent]
  def addSubstrateComponent(data: SubstrateComponentData): CatalogAddResult[SubstrateComponent]
  def editSubstrateComponent(id: SubstrateComponentId, data: SubstrateComponentData): CatalogEditResult[SubstrateComponent]

trait PesticideCatalog:
  def getPesticides: CatalogReadResult[Pesticide]
  def addPesticide(data: PesticideData): CatalogAddResult[Pesticide]
  def editPesticide(id: PesticideId, data: PesticideData): CatalogEditResult[Pesticide]
```

Persistence ports redistribute existing methods. The specialized plant-archive write is replaced by the existing plant update, which persists both status and substrate:

```scala
trait PlantJournalStore:
  def getPlants(status: PlantStatus): GetPlantsResult
  def getArchivedCount: ArchivedCountResult
  def getPlant(id: PlantId): GetPlantResult
  def getOperations(plantId: PlantId, window: OperationWindow): GetOperationsResult
  def getOperationDateRange(plantId: PlantId): GetOperationDateRangeResult
  def getOperation(id: OperationId): GetOperationResult
  def addOperation(operation: Operation): LogOperationResult
  def updateOperation(id: OperationId, details: OperationDetails): EditOperationResult
  def removeOperation(id: OperationId): OperationCompensationResult
  def restoreOperation(operation: Operation): OperationCompensationResult
  def updatePlant(plant: Plant): UpdatePlantResult

trait PlantAttentionStore:
  def getAttentionSamples(size: WateringSampleSize): GetAttentionSamplesResult

trait SubstrateComponentStore:
  def getSubstrateComponents: CatalogReadResult[SubstrateComponent]
  def addSubstrateComponent(component: SubstrateComponent): CatalogAddResult[SubstrateComponent]
  def editSubstrateComponent(id: SubstrateComponentId, data: SubstrateComponentData): CatalogEditResult[SubstrateComponent]

trait PesticideStore:
  def getPesticides: CatalogReadResult[Pesticide]
  def addPesticide(pesticide: Pesticide): CatalogAddResult[Pesticide]
  def editPesticide(id: PesticideId, data: PesticideData): CatalogEditResult[Pesticide]
```

Plant and operation behavior stays in `PlantJournal` and `PlantJournalStore`. The journal's archive use case reads the plant and persists its status change through `updatePlant`. The two catalogs own only their existing methods and persistence. The browser keeps one journal client for plants/operations and moves only attention and catalog methods into their own clients, without changing method signatures. Journal and attention persistence may remain together; the two catalogs have independent persistence and encoding responsibilities.

## Invariants

- A latest repot determines the plant's current substrate; failed post-write plant reads or updates retain their existing restoration and error behavior.
- Operation identity, plant, date, and kind remain immutable on edit. A failed attention refresh retains the last complete projection; pagination and catalog-reference validation retain their existing outcomes.

## Tradeoffs Accepted

- The changed paths are breaking for older clients; the bundled browser client and generated contract change together without parallel aliases.
- Archiving waits for a full attention refresh instead of editing the cached projection; the extra read is acceptable at household scale.

## Acceptance Criteria

- Every existing operation appears exactly once in the [route contract](contracts.md), with the same HTTP verb, inputs, output, and failure classification. Only listed paths change; no single-plant or single-operation read is added.
- Plant and operation routes share the journal capability and persistence port. The journal's archive use case retains success, missing-plant, already-archived, and write-failure outcomes through the existing `getPlant` and `updatePlant` port methods. Attention keeps `current` and `refreshAll` and drops `removeArchivedPlant`; each catalog gets its existing methods under its own trait and port. Moved browser methods retain their signatures.
- After a successful archive, one full attention refresh runs before the response. Success excludes the archived plant; refresh failure retains the prior complete projection without undoing the archive, and the scheduled refresh may recover it.
- Garden and cemetery browsing, operation logging/editing, and inline catalog editing retain their loading, error, keyboard, and focus behavior.

## Doc Sync

- `specs/design.md` — Service overview, Component architecture, and Processing rules for the journal, attention refresh, and catalog boundaries.
- `specs/contracts.md` — HTTP API and Versioning & compatibility for changed paths with unchanged operations.
- `specs/testing.md` — Service-specific strategy, Fixtures & data setup, and Integration boundaries for separate HTTP and catalog persistence seams.

## Out of Scope

- Saved substrate-mix favorites or aliases.
- Hypermedia discoverability links.
