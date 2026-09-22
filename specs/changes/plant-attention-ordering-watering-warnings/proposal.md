# Plant attention ordering and watering warnings

> Standard: [Agentic Engineering Standards](https://github.com/Adobe-AIFoundations/agentic-workflow-standards) v1.2.0.
> Lifetime: open from creation through implementation, archived in the [sync docs & archive step](../../agentic-workflows.md#a-structured-development-skills).

**Date:** 2026-09-22

Derive plant attention from bounded watering history and publish it for presentation and future metrics export.

## What & Why

- Active plants are ordered by display name in the browser; watering cadence, urgency, alert state, and attention ordering are not modelled.
- The backend will own materialized attention measurements through a vendor-neutral read port. The browser will order those measurements for presentation; a future metrics adapter can consume them without UI ordering semantics.

## Domain / Design Notes

```scala
final case class AttentionProjection(measuredAt: Instant, plants: Vector[PlantAttention])
final case class PlantAttention(plant: Plant, cadence: WateringCadence)
type WateringSampleCount = Int :| Interval.Closed[0, 20]
type OperationPageSize = Int :| Interval.Closed[1, 20]

enum Urgency:
  case Finite(elapsed: Duration, averageInterval: Duration)
  case Unbounded

enum WateringCadence:
  case Unavailable(sampleCount: WateringSampleCount, maybeElapsed: Option[Duration])
  case Inferred(
      sampleCount: WateringSampleCount,
      averageInterval: Duration,
      elapsed: Duration,
      urgency: Urgency,
      state: WateringState
  )

enum WateringState:
  case Current, Overdue, RedAlert

enum OperationSelection:
  case All, Watering

enum RefreshAttentionResult:
  case Refreshed(projection: AttentionProjection)
  case RefreshFailed(reason: Throwable)

trait PlantAttentionService:
  def current: AttentionProjection
  def refreshAll: RefreshAttentionResult

trait PlantJournalStore:
  def getOperations(plantId: PlantId, selection: OperationSelection, window: OperationWindow): GetOperationsResult
  // Existing plant, operation-mutation, and catalog capabilities are unchanged.
```

- `WateringSampleCount` is 0–20. The shared bounded operation read accepts an `All` or `Watering` selection and a requested size up to 20; selection, timestamp-descending order, identifier tie-break, and limit are applied by persistence.
- Plant and operation values are shared domain concepts. Journal mutation and retrieval contracts and attention calculation and projection contracts remain separate subdomains.
- Cadence uses the latest 5–20 watering-selected operations. Fewer than five is unavailable; otherwise the average is the arithmetic mean of consecutive timestamps.
- Urgency is the exact elapsed/average ratio, not a floating-point approximation. A zero average has zero urgency at zero elapsed and unbounded urgency after time advances.
- State is `Current` through the average interval, `Overdue` immediately after it, and `RedAlert` at average plus 24 hours. Unknown cadence has no state.
- Browser ordering is unknown cadence first, then inferred cadence by exact urgency descending. Ties use location, species, nickname with absence before presence, then plant identifier, all ascending.
- Attention is materialized during startup and recomputed every five minutes. Startup failure aborts the application; later failure retains the prior measurement. Journal changes become visible on the next recomputation, and publication replaces the snapshot atomically.
- HTTP translates attention outside the core without adding presentation order. The browser reads it on load and after a successful operation save; a future metrics adapter can read the same port without determining scrape versus push now.

## Invariants

- Recent cards still request three unfiltered operations; history still requests up to ten unfiltered operations per page with existing ordering, defaults, and failure behavior.
- Journal persistence, compensation, mutation serialization, and focus restoration remain unchanged.
- HTTP and future metrics models stay outside the core domain.

## Tradeoffs Accepted

- Materialization adds bounded background work and can lag wall time by up to five minutes. A failed refresh does not roll back a successful journal mutation.

## Acceptance Criteria

- Cadence results are correct for fewer than 5, exactly 5, and more than 20 qualifying waterings; non-watering care and repots are excluded before the 20-operation limit.
- Equal timestamps produce deterministic attention values. In the browser, unknown plants precede scored plants; higher urgency precedes lower urgency; and urgency ties use the defined plant-field order, placing recently watered scored plants near the bottom.
- At the average interval a plant is current; immediately after it is overdue; immediately below average plus 24 hours it remains overdue; at and above that threshold it is red alert. Unknown cadence has no overdue or red-alert state.
- Startup and each five-minute interval publish a newly measured complete projection, including watering logs and edits that changed stored qualifying operations since the prior measurement. Startup failure aborts the application; a later refresh failure never exposes a partial projection.
- The browser orders attention values and preserves backend state. Red-alert cards show a large `!` with an accessible `Watering red alert` name and warning semantics; overdue and unknown cadence are distinct without relying on color. Card controls, keyboard focus, desktop layout, and landscape-mobile layout remain usable after reorder.
- The operation-read API remains bounded for all callers. The attention read port contains measurement time, sample count, average interval, elapsed time, urgency, and state as available, without metrics-vendor dependencies.

## Doc Sync

- `GLOSSARY.md` — define plant attention, watering cadence, urgency, overdue, and red alert.
- `specs/design.md` — Domain model, Processing rules, Edge cases, and Component architecture for attention and its future metrics seam.
- `specs/contracts.md` — HTTP API and error behavior for the attention projection.
- `specs/testing.md` — Service-specific strategy, fixtures, and integration boundaries for attention behavior and presentation.
- `specs/operational.md` — Scaling characteristics of five-minute materialization.

## Out of Scope

- VictoriaMetrics export, configuration, export scheduling, scrape/push choice, and adapters.
- Predictive cadence models beyond the arithmetic mean.
