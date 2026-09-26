# Business and performance metrics

> Standard: [Agentic Engineering Standards](https://github.com/Adobe-AIFoundations/agentic-workflow-standards) v1.2.0.
> Lifetime: open from creation through implementation, archived in the [sync docs & archive step](../../agentic-workflows.md#a-structured-development-skills).

**Date:** 2026-09-25

Add Prometheus-format metrics and a committed Grafana dashboard, so the household can see plant/care/attention trends and backend health without reading logs.

## What & Why

- Today: no metrics of any kind. The only operational signal is the single-line log output added by the logging change; there is no way to see business trends (how many plants, how often care is logged, how many plants are overdue) or backend health (request latency, error rate, process resource use) without reading raw logs.
- New: the backend exposes one `GET /metrics` endpoint in Prometheus exposition format, scraped by the NAS's existing Victoria Metrics instance, backing a Grafana dashboard committed to this repo. Three metric families, each owned at a different layer:
  - **Business** — recorded in the domain, at the points that already log: plant lifecycle events, logged-operation detail (action types, moisture, substrate, pesticides), and per-plant watering urgency and cadence — chosen for trends the app's current-state view cannot show, not counts of what the UI already displays.
  - **HTTP RED** (rate, errors, duration) — request count and duration per declared endpoint path template and method, owned entirely by the HTTP transport.
  - **Process USE** (utilization/saturation/errors) — JVM/process-level CPU, memory, GC, and thread metrics, complementing the NAS's existing cAdvisor container metrics with JVM-internal detail cAdvisor cannot see.
- No distributed tracing, and no OpenTelemetry: a single-process, single-instance backend has no cross-service spans to correlate, and Victoria Metrics only ingests the Prometheus exposition format these three families already produce.

## Domain / Design Notes

- Business metrics are domain-owned, threaded via `using` exactly like `Logger` — substituted in tests, never global. RED and process metrics carry no business meaning; they're wired once in the composition root with no domain threading.
- One `*MetricsApi` port per domain trait with signal worth publishing, not one catalog-all trait. Each port is as low-level as a persistence port: every method increments one counter or sets one gauge for one label value. The domain decides *which* calls a business event maps to, by pattern-matching its own types — swap the metrics backend later, and only "increment"/"set" needs reimplementing.

```scala
trait PlantJournalMetricsApi:
  def setPlantsCount(status: PlantStatus, count: Long): Unit
  def incrementAction(kind: ActionType): Unit
  def incrementRepot(plant: PlantId): Unit
  def incrementMoisture(level: MoistureLevel): Unit
  def incrementSubstrateComponent(component: SubstrateComponentId): Unit
  def incrementPesticide(pesticide: PesticideId): Unit

trait PlantAttentionMonitorMetricsApi:
  def setWateringUrgencyRatio(plant: PlantId, ratio: Double): Unit
  def setWateringCadence(plant: PlantId, cadence: FiniteDuration): Unit
```

- `setPlantsCount` and the two watering setters are gauges re-derived from a real read every time, never incremented/decremented — a missed update self-corrects on the next read instead of compounding forever. Everything else counts *how often*, not a current total, so a counter plus `rate()`/`increase()` is correct.
- No household-wide repot counter: `incrementRepot` always carries a plant; the household total is `sum(gardening_journal_repots_total)` with the label dropped, not a second series to keep in sync.

`PlantJournal.logOperation`'s success branch fans one logged detail out into the calls that fact deserves, decided entirely by the domain:

```scala
op match
  case care: OperationDetails.Care =>
    care.actions.foreach(metrics.incrementAction)
    metrics.incrementMoisture(care.moisture)
    care.pesticides.foreach(metrics.incrementPesticide)
  case repot: OperationDetails.Repot =>
    metrics.incrementRepot(plantId)
    repot.substrate.parts.foreach(part => metrics.incrementSubstrateComponent(part.componentId))
```

- Fires after the existing `log.info`, once repot-compensation resolves; a compensated failure calls none of these.
- `createPlant`'s success branch also calls `incrementSubstrateComponent` per part of the plant's *initial* substrate, so usage tracking covers both origins of a mix.
- `getPlants`/`getArchivedCount` call `setPlantsCount` with whichever status/count they just read — the only two call sites, both pre-existing.
- Editing or deleting an operation calls none of the above: replaying corrected or removed history into these counters would double-count, or falsely un-count, care that already happened.
- Both ports thread into `PlantJournal.make`/`PlantAttentionMonitor.make` via `using`, the same shape `Logger` already uses (see the backend-logging spec).

`PlantAttentionMonitor.refreshAll` — and its startup projection in `make` — sets both watering gauges for every plant with an available assessment (`WateringAttention.Available`): `setWateringUrgencyRatio(elapsed / averageInterval)`, `setWateringCadence(averageInterval)`. A plant without enough watering history gets neither call, reporting no value rather than a stale one.

Names follow Prometheus's own convention, not OpenTelemetry's — this backend emits Prometheus exposition directly, never OTLP:

- `<namespace>_<subsystem>_<name>_<unit>`
- `_total` only for a monotonic counter, never a gauge
- base units only (`seconds`, never `hours`)
- `_ratio` for a dimensionless proportion
- `journal`/`attention` subsystems match the domain package names that own each metric

**Metric inventory.** The eight business rows are ours to name. The three request rows are tapir's own default metric set (`PrometheusMetrics.default`), reproduced here under our `namespace = "gardening"` override so the whole `/metrics` surface is in one table — confirmed against the `tapir-prometheus-metrics` 1.13.31 source, which builds every one of its names as `s"${namespace}_${metricName}"`, so the override is real, not assumed.

| Metric | Type | Labels |
| --- | --- | --- |
| `gardening_journal_plants` | Gauge | `status` (active, archived) |
| `gardening_attention_urgency_ratio` | Gauge | `plant` |
| `gardening_attention_watering_cadence_seconds` | Gauge | `plant` |
| `gardening_journal_operations_total` | Counter | `action` (watered, fertilized, pesticide, pruned, noAction) |
| `gardening_journal_moisture_readings_total` | Counter | `level` (wet, moderatePlus, moderateMinus, dry, noReading) |
| `gardening_journal_repots_total` | Counter | `plant` |
| `gardening_journal_substrate_component_usage_total` | Counter | `component` |
| `gardening_journal_pesticide_applications_total` | Counter | `pesticide` |
| `gardening_request_total` (tapir) | Counter | `path`, `method`, `status` |
| `gardening_request_active` (tapir) | Gauge | `path`, `method` |
| `gardening_request_duration_seconds` (tapir) | Histogram | `path`, `method`, `status` |

- The two watering gauges are set only for a plant with an available assessment; an unavailable plant reports neither, the same case the app itself shows instead of a number.
- Process/JVM series (`process_cpu_seconds_total`, `jvm_memory_used_bytes`, etc.) aren't itemized — the library names them, not this change. The NAS's existing cAdvisor container metrics aren't itemized or added by this change either — Victoria Metrics already scrapes cAdvisor independently, the same way it backs the existing Infra dashboard.

**Composition, RED, USE.**

- One `PrometheusRegistry` (the current, non-deprecated Prometheus Java client registry, not the deprecated simpleclient one) is built once in the composition root, the same way `Clock`/`IdGenerator`/`Logger` already are. Tapir's request metrics, JVM process instrumentation, and each `Prometheus<Trait>Metrics` adapter all register into it; `GET /metrics` scrapes that one registry.
- RED: every endpoint is labeled by its declared path template and method, never a real ID, by construction — covering archived-count, patch, static-file, and the attention WebSocket upgrade (measured as one ordinary request: handshake duration only).
- USE: cAdvisor stays authoritative for container CPU/memory/network/PSI (already scraped, backs the existing Infra dashboard); this change adds JVM-internal detail cAdvisor can't see (heap/non-heap, GC pause, thread count), registered at startup so it's present in the first scrape. The two sources are independently scraped — neither depends on the other staying up.

**Grafana dashboard.** Committed to this repo, deployed the same way as the existing Insights/Infra dashboards: a one-shot script copies the JSON to Grafana's file-provisioning directory (10s poll, matched by hardcoded UID) — no API call, no restart, no live sync.

- Layout: business on top (the eight metrics above, two rows of four), RED middle, USE bottom (cAdvisor panels alongside this change's process/JVM panels).
- 24-unit grid, panels tiled four across (`w=6`); every business panel is `timeseries` — no `stat` panel, since the point is a trend.
- Every query multi-line and indented, one label matcher per line.
- Per-plant series (urgency ratio, cadence, repots) use `topk($top_k, ...)`, reusing the `$top_k` variable the Infra dashboard already established.
- No environment/region templating — one NAS, one instance.

## Alternatives Considered

- Micrometer was rejected: tapir's own metrics integration targets the Prometheus Java client registry directly, and a second metrics facade on top would duplicate what `tapir-prometheus-metrics` and the JVM instrumentation module already provide.

## Acceptance Criteria

- A single `GET /metrics` response contains all three families together: at least one business metric, the transport request metrics, and the process metrics — proving the shared-registry design, not three separate endpoints.
- Every successful `getPlants`/`getArchivedCount` read sets `gardening_journal_plants` to exactly the count it just returned, for the status it read; a failed read leaves the previous value in place.
- Logging a care operation increments `gardening_journal_operations_total` once per action type it carries, `gardening_journal_moisture_readings_total` for its recorded level, and `gardening_journal_pesticide_applications_total` once per selected pesticide; logging a repot increments `gardening_journal_repots_total` for that plant and `gardening_journal_substrate_component_usage_total` once per component in the new mix — all only on success. Editing or deleting either kind of operation increments none of them.
- Creating a plant increments `gardening_journal_substrate_component_usage_total` once per component in its initial mix on success; a failed attempt increments nothing.
- `gardening_attention_urgency_ratio` and `gardening_attention_watering_cadence_seconds` carry a value for every plant with an available assessment after each completed recomputation, including the one at startup, and no value for a plant with too little watering history to assess.
- Every HTTP endpoint's request metrics are labeled by its declared path template and method — never a real plant, operation, substrate-component, or pesticide identifier — including the attention WebSocket upgrade and static-asset serving.
- Process metrics are present in the first scrape taken immediately after startup, before any request has been served.
- A dashboard loaded into Grafana from the committed file renders every panel without an unknown-metric or broken-query error, and its per-plant panels respect the dashboard's top-N variable rather than always plotting every plant unconditionally; copying it to the NAS with its push script results in Grafana loading or updating that same dashboard (matched by its hardcoded UID) within one provisioning scan, with no manual UI step.

## Doc Sync

- `CONTRIBUTING.md` — new "Metrics" section immediately after "Logging": business metrics are chosen for a trend, never a restatement of current state the UI already shows; each `*MetricsApi` port is low-level — one counter increment or one gauge set per call, the domain service decides which, the adapter never interprets a business object; a count with a current true state is a gauge re-derived from a read, never an accumulator, so a missed update self-corrects on the next read rather than compounding; recorded on the success path of the same call sites logging already uses, after any compensation resolves, and never on an edit or delete of already-recorded history; RED and process metrics are transport/JVM-owned with no domain threading; one shared registry backs `/metrics`.
- `.agents/skills/sdd/SKILL.md` — implementation checklist gains a permanent item alongside the existing logging one: record business metrics at the same effect boundary as domain logging, via a low-level capability port named for the owning domain trait — the port only increments or sets, the domain decides what — never inside a pure function; prefer a gauge re-derived from source of truth over an accumulator wherever a current total exists.
- `specs/design.md` — Architecture constraints: the three-layer metrics split (domain capability for business, transport/JVM for RED and USE) and the single shared registry behind one `/metrics` endpoint.
- `specs/contracts.md` — Contract inventory: `GET /metrics` (Prometheus exposition) as an HTTP surface outside the generated OpenAPI (like the WebSocket feed); `PlantJournalMetricsApi` and `PlantAttentionMonitorMetricsApi` listed alongside the existing domain service and capability ports.
- `specs/operational.md` — Alerts: `/metrics` is now the operational signal, still with no alerting or aggregation configured.
- `specs/operational.md` — Deployment topology: the committed dashboard and its scp-based push script targeting Grafana's file-provisioning directory on the NAS; that this backend depends on a Victoria Metrics scrape-config addition made outside this repo; that Victoria Metrics scrapes `/metrics` directly (no push gateway).

## Out of Scope

- Metrics for `SubstrateComponentCatalog` and `PesticideCatalog` — the same `*MetricsApi` pattern extends to them later if their catalogs' size or edit rate becomes a question worth answering.
- Distributed tracing and OpenTelemetry; alerting rules on any metric — dashboard-only for now.
