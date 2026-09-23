# Resource-oriented plant care boundaries

> Standard: [Agentic Engineering Standards](https://github.com/Adobe-AIFoundations/agentic-workflow-standards) v1.2.0.
> Lifetime: open from creation through implementation, archived in the [SDD archive step](../../../.agents/skills/sdd/SKILL.md).

**Date:** 2026-09-24

Split the plant-care contract by resource; see the [proposed HTTP paths and verbs](contracts.md).

## What & Why

The HTTP routes already distinguish most resources, but a single journal service, browser client, and persistence adapter own plants, operations, and two unrelated catalogs. Each resource gets its own API and domain capability; plants and operations remain the journal because a repot changes both. Archived-plant behavior is a prerequisite: its implementation must merge before this change is implemented.

## Domain / Design Notes

Proposed service traits use the post-prerequisite results. Logging distinguishes the cases needed for the new operation collection:

```scala
enum LogOperationResult:
  case Logged(id: OperationId)
  case PlantMissing
  case PlantArchived
  case LoggingFailed(reason: Throwable)
```

```scala
trait PlantService:
  def getPlants(status: PlantStatus): GetPlantsResult
  def getPlant(id: PlantId): GetPlantResult
  def getArchivedCount: ArchivedCountResult
  def archivePlant(id: PlantId): ArchivePlantResult

trait OperationService:
  def getOperations(plantId: PlantId, window: OperationWindow): GetOperationsResult
  def getOperationDateRange(plantId: PlantId): GetOperationDateRangeResult
  def getOperation(id: OperationId): GetOperationResult
  def logOperation(plantId: PlantId, date: Instant, details: OperationDetails): LogOperationResult
  def editOperation(id: OperationId, details: OperationDetails): EditOperationResult

trait PlantAttentionMonitor:
  def current: AttentionProjection
  def refreshAll: RefreshAttentionResult
  def removeArchivedPlant(id: PlantId): Unit

trait SubstrateComponentCatalog:
  def getComponents: CatalogReadResult[SubstrateComponent]
  def addComponent(data: SubstrateComponentData): CatalogAddResult[SubstrateComponent]
  def editComponent(id: SubstrateComponentId, data: SubstrateComponentData): CatalogEditResult[SubstrateComponent]

trait PesticideCatalog:
  def getPesticides: CatalogReadResult[Pesticide]
  def addPesticide(data: PesticideData): CatalogAddResult[Pesticide]
  def editPesticide(id: PesticideId, data: PesticideData): CatalogEditResult[Pesticide]
```

Proposed persistence ports (plant and operation writes share one journal consistency boundary):

```scala
trait PlantStore:
  def getPlants(status: PlantStatus): GetPlantsResult
  def getPlant(id: PlantId): GetPlantResult
  def getArchivedCount: ArchivedCountResult
  def archivePlant(id: PlantId): ArchivePlantResult
  def updatePlant(plant: Plant): UpdatePlantResult

trait OperationStore:
  def getOperations(plantId: PlantId, window: OperationWindow): GetOperationsResult
  def getOperationDateRange(plantId: PlantId): GetOperationDateRangeResult
  def getOperation(id: OperationId): GetOperationResult
  def addOperation(operation: Operation): LogOperationResult
  def updateOperation(id: OperationId, details: OperationDetails): EditOperationResult
  def removeOperation(id: OperationId): OperationCompensationResult
  def restoreOperation(operation: Operation): OperationCompensationResult

trait PlantAttentionStore:
  def getAttentionSamples(size: WateringSampleSize): GetAttentionSamplesResult

trait SubstrateComponentStore:
  def getComponents: CatalogReadResult[SubstrateComponent]
  def addComponent(component: SubstrateComponent): CatalogAddResult[SubstrateComponent]
  def editComponent(id: SubstrateComponentId, data: SubstrateComponentData): CatalogEditResult[SubstrateComponent]

trait PesticideStore:
  def getPesticides: CatalogReadResult[Pesticide]
  def addPesticide(pesticide: Pesticide): CatalogAddResult[Pesticide]
  def editPesticide(id: PesticideId, data: PesticideData): CatalogEditResult[Pesticide]
```

`OperationService` uses `PlantStore` for plant existence/current substrate and the two catalog ports for reference validation. `PlantService` and `OperationService` serialize writes through a shared journal boundary; `PlantService` removes a newly archived plant from the current attention projection after a successful archive. The browser has corresponding asynchronous resource clients rather than one journal client. Journal/attention persistence can stay grouped; substrate components and pesticides own independent persistence and codecs.

## Invariants

- An operation's identity, plant, date, and kind do not change on edit; users cannot delete operations.
- The latest repot determines the plant's current substrate. A failed post-write update compensates the operation write and preserves both causes if compensation also fails.
- Catalog IDs remain stable on edit. Care and repot refer only to known pesticides and substrate components.
- Attention is measured for active plants independently of their current details; a failed refresh retains the last complete projection.

## Tradeoffs Accepted

- The operation collection/date-range, substrate-component catalog, and archive transition change HTTP paths. The generated contract and bundled browser client change together; old aliases are not retained.
- Single-record plant and operation reads become public so each identified resource is addressable. No new mutation capability follows from those reads.

## Acceptance Criteria

- Every row in the [proposed HTTP contract](contracts.md) has its stated method, path, success response, and failure behavior. Endpoints delegate to the corresponding service, with no journal service exposing catalog methods or catalog persistence serving journal/attention records.
- A plant and an operation can each be read by identity; unknown identities return not found. Collection reads retain active-by-default plants, bounded operation pages, archived counts, and empty history for an unknown plant; attention remains a read-only, active-plant projection.
- The garden and cemetery retain their supported loading, ordering, pagination, inline catalog editing, date presentation, and keyboard/focus behavior with separate resource clients. Failed resource reads/writes remain visible failures, not partial or stale success.
- Invalid status, date, operation window, or catalog data remain input errors; missing records, already archived plants, and operation-kind conflicts retain their distinct outcomes. Invalid catalog references cannot create or change an operation; failed archive writes leave the plant unchanged and report failure.

## Doc Sync

- `specs/design.md` — Service overview, Component architecture, Processing rules, and Edge cases for the five capabilities, single-record reads, and shared journal consistency.
- `specs/contracts.md` — HTTP API, Error responses, and Versioning & compatibility for the final resource paths.
- `specs/testing.md` — Service-specific strategy, Fixtures & data setup, and Integration boundaries for the independent HTTP, service, and persistence seams.

## Out of Scope

- Saved substrate-mix favorites or aliases.
- Hypermedia discoverability links.
