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

**Business metrics are a domain capability; RED and process metrics are not.** Business metrics carry meaning only the domain knows (a logged operation's kind, an attention level), so they are recorded in the domain services, threaded exactly like `Logger` — a capability resolved with `using`, substituted in tests, never a global. RED and process metrics carry no business meaning; they belong entirely to the HTTP transport and the JVM, wired once in the composition root, with no domain threading at all.

**One `*MetricsApi` port per domain trait with business signal to publish** — not one catalog-all trait. Each port is deliberately low-level, the same granularity as a persistence port: every method increments one counter or sets one gauge for one label value — nothing takes a whole business object for the adapter to interpret. The owning domain service decides *which* of these calls a business event maps to, by pattern-matching its own domain types before calling out. Swap the concrete metrics backend later, and only "increment this counter" / "set this gauge" needs reimplementing — deciding what a `Care` operation's action types or a `Repot`'s substrate parts mean never moves, because it never left the domain.

```scala
trait PlantJournalMetricsApi:
  def setPlantsCount(status: PlantStatus, count: Long): Unit
  def incrementAction(kind: ActionType): Unit
  def incrementRepot(): Unit
  def incrementRepot(plant: PlantId): Unit
  def incrementMoisture(level: MoistureLevel): Unit
  def incrementSubstrateComponent(component: SubstrateComponentId): Unit
  def incrementPesticide(pesticide: PesticideId): Unit

trait PlantAttentionMonitorMetricsApi:
  def setWateringUrgencyRatio(plant: PlantId, ratio: Double): Unit
  def setWateringCadence(plant: PlantId, hours: Double): Unit
```

`setPlantsCount` is a gauge, not an accumulator, reused across both statuses by its caller — set to the exact count a read just returned, every time that read happens, never incremented on create or decremented on archive. A counter that only reacts to create/archive events can only get *more* wrong over time if any single one is ever missed — a failed write, a manual fix applied straight to the database, a bug; a gauge re-derived from the real row count self-corrects on the very next read regardless of what happened before it. `setWateringUrgencyRatio` and `setWateringCadence` already have this property for the same reason: each recomputation re-derives both from the plant's actual watering history, never from the previous scrape's value.

Everything else here counts *how often* something happened, not a current total — there is no "true state" to re-derive for "how many times has this occurred", so a counter plus `rate()`/`increase()` is the right tool, not a drift risk. `incrementRepot` is overloaded by arity, not two differently-named methods, the same way `Logger.error` already is — one call for the household total, one adding the per-plant series, both the same underlying fact.

`PlantJournal.logOperation`'s success branch fans one logged detail out into several of these calls — one per fact worth its own series, decided entirely by the domain:

```scala
op match
  case care: OperationDetails.Care =>
    care.actions.foreach(metrics.incrementAction)
    metrics.incrementMoisture(care.moisture)
    care.pesticides.foreach(metrics.incrementPesticide)
  case repot: OperationDetails.Repot =>
    metrics.incrementRepot()
    metrics.incrementRepot(plantId)
    repot.substrate.parts.foreach(part => metrics.incrementSubstrateComponent(part.componentId))
```

This fires after `logOperation`'s existing `log.info`, once its repot-compensation step resolves — a compensated repot failure returns a failure result and calls none of these. `createPlant`'s success branch calls the same `incrementSubstrateComponent` for each part of a plant's *initial* substrate, so usage tracking covers both origins of a mix, not just repots. `getPlants`/`getArchivedCount` call `setPlantsCount` with whichever status and count they just read — the only two call sites that touch it, both already existing today, unlogged on success. Editing or deleting an operation calls none of the above: both correct or remove already-recorded history, and replaying them into these counters would double-count (or, for a delete, falsely un-count) care that happened regardless of what the journal now says about it.

Threading follows the same before/after shape logging introduced:

```scala
// before
def make(using store: PlantJournalStore^, substrateStore: SubstrateComponentStore^, pesticideStore: PesticideStore^, idGen: IdGenerator^)
    (using log: Logger^): PlantJournal^{store, substrateStore, pesticideStore, idGen, log}

// after
def make(using store: PlantJournalStore^, substrateStore: SubstrateComponentStore^, pesticideStore: PesticideStore^, idGen: IdGenerator^)
    (using log: Logger^, metrics: PlantJournalMetricsApi^): PlantJournal^{store, substrateStore, pesticideStore, idGen, log, metrics}
```

`PlantAttentionMonitor.refreshAll` interprets its own projection the same way before calling out: for each plant with an available assessment (`WateringAttention.Available`), it calls `setWateringUrgencyRatio` with `elapsed / averageInterval` and `setWateringCadence` with `averageInterval` in hours; a plant without enough watering history calls neither, so it reports no value rather than a stale or zero one. `PlantAttentionMonitor.make` calls the same two setters for the projection it computes at startup, not only inside `refreshAll` — these gauges need real values before the first scheduled recomputation runs (minutes away), even though today's logging deliberately covers only `refreshAll`. `PlantAttentionMonitor.make` and `PlantJournal.make` are both threaded the same way as the diff above.

**Metric inventory** — every series this change adds, by owning layer:

| Metric | Type | Labels |
| --- | --- | --- |
| `gardening_plants_total` | Gauge | `status` (active, archived) |
| `gardening_watering_urgency_ratio` | Gauge | `plant` |
| `gardening_watering_cadence_hours` | Gauge | `plant` |
| `gardening_operations_total` | Counter | `type` (watered, fertilized, pesticide, pruned, noAction, repot) |
| `gardening_moisture_readings_total` | Counter | `level` (wet, moderatePlus, moderateMinus, dry, noReading) |
| `gardening_repots_total` | Counter | `plant` |
| `gardening_substrate_component_usage_total` | Counter | `component` |
| `gardening_pesticide_applications_total` | Counter | `pesticide` |
| `gardening_request_total` | Counter | `path`, `method`, `status` |
| `gardening_request_active` | Gauge | `path`, `method` |
| `gardening_request_duration_seconds` | Histogram | `path`, `method`, `status` |

`gardening_watering_urgency_ratio` and `gardening_watering_cadence_hours` are set only for a plant with an available assessment (`WateringAttention.Available` — five or more waterings); a plant with too little history to assess reports neither series, the same "unavailable" case the app itself shows instead of a number.

Process/JVM series (`process_cpu_seconds_total`, `jvm_memory_used_bytes`, `jvm_gc_pause_seconds`, `jvm_threads_current`, and the rest of that standard instrumentation set) are not itemized here — their names and types are the library's, not this change's, to define. The NAS's existing cAdvisor container metrics (`container_cpu_usage_seconds_total`, `container_memory_working_set_bytes`, `container_network_{receive,transmit}_bytes_total`, and the cgroup-v2 PSI series `container_pressure_cpu_stalled_seconds_total`, all labeled `name="plant-journal"` for this container) are not itemized either, and not added by this change at all — Victoria Metrics already scrapes cAdvisor independently of this backend's own `/metrics`, the same way it already backs the existing Infra dashboard. The dashboard queries both sources; only the process/JVM series go through the registry this change builds.

**Composition — one registry, one scrape.** A single registry (the Prometheus Java client's current, non-deprecated `PrometheusRegistry` — see Alternatives Considered) is built once in the composition root, the same way `Clock`, `IdGenerator`, and `Logger` are already built once and threaded via `using`. The transport's request metrics, the JVM process instrumentation, and each `Prometheus<Trait>Metrics` business adapter all register their collectors into that same registry. `GET /metrics` scrapes the one registry, so one response always carries all three families — there is no second registry for anything to fall out of sync with.

**RED specifics.** Every endpoint's request metrics are labeled by its *declared* path template and method — `/plants/{plantId}`, never the real identifier that was requested — by construction, covering the archived-count, patch, and static-file routes too. The attention WebSocket upgrade is measured as one ordinary request: its duration is the handshake time, not the socket's open lifetime; the feed itself carries no further RED signal.

**USE specifics.** The NAS's existing cAdvisor deployment is the authoritative source for this container's utilization and saturation — CPU and memory usage, network throughput, and cgroup-v2 PSI (the real saturation signal: time spent stalled waiting on a resource) — already scraped by Victoria Metrics the same way it already backs the existing Infra dashboard. This change adds process/JVM metrics alongside it: internal detail (heap/non-heap split, GC pause time, live thread count) a container-level view cannot see, registered once at startup — before any request, so they appear in the very first scrape.

**Grafana dashboard.** Committed to this repo, deployed the same way every other dashboard on this Grafana instance is: a one-shot script copies the JSON to the NAS's Grafana file-provisioning directory (the same mechanism already serving the Insights and Infra dashboards); Grafana's file provider polls that directory every 10s and loads or updates the dashboard by its hardcoded UID — no API call, no restart, no live sync from this repo's side. Layout mirrors the panel/query quality bar of the reference `ingest-controller` dashboard, scaled to a single-instance service: business on top (the eight metrics above, two rows of four), HTTP RED in the middle, USE at the bottom (cAdvisor's container panels alongside this change's process/JVM panels); content panels tiled four across a 24-unit grid (`w=6`); every business panel is `timeseries` — the whole point of this section is a trend, so there is no `stat`/current-value panel to have; every query multi-line and indented, one label matcher per line. The three per-plant series (urgency ratio, cadence, repots) query `topk($top_k, ...)`, reusing the `$top_k` templating variable the NAS's existing Infra dashboard already established, so "top 10 most urgent" is a variable change, not a different panel. Unlike the reference dashboard, there is no environment/region templating — one NAS, one instance.

## Alternatives Considered

- `tapir-prometheus-simpleclient-metrics` (matching the reference repo's `io.prometheus.client.CollectorRegistry`) was rejected: it is deprecated and scheduled for removal, and this repo's build guardrails treat carrying a deprecated dependency as a defect to pay down, not accept.
- Micrometer was rejected: tapir's own metrics integration targets the Prometheus Java client registry directly, and adding a second metrics facade on top would duplicate what `tapir-prometheus-metrics` and the JVM instrumentation module already provide.

## Tradeoffs Accepted

- The dashboard's USE row spans two independently-scraped sources — cAdvisor's pre-existing container metrics and this change's own process/JVM metrics — rather than one. Acceptable because both already land in the same Victoria Metrics instance Grafana queries; this backend has no dependency on cAdvisor beyond assuming it keeps running, and loses nothing if it doesn't (the JVM series stand on their own).
- This backend being scraped at all depends on one addition to Victoria Metrics' existing scrape config — a new job pointed at this container, made outside this repo (NAS-side, alongside the existing home-assistant and cadvisor jobs). Acceptable: every other scrape target here was added the same way, and VM reloads its scrape config on file change with no restart.

## Acceptance Criteria

- A single `GET /metrics` response contains all three families together: at least one business metric, the transport request metrics, and the process metrics — proving the shared-registry design, not three separate endpoints.
- Every successful `getPlants`/`getArchivedCount` read sets `gardening_plants_total` to exactly the count it just returned, for the status it read; a failed read leaves the previous value in place.
- Logging a care operation increments `gardening_operations_total` once per action type it carries, `gardening_moisture_readings_total` for its recorded level, and `gardening_pesticide_applications_total` once per selected pesticide; logging a repot increments `gardening_operations_total{type="repot"}`, `gardening_repots_total` for that plant, and `gardening_substrate_component_usage_total` once per component in the new mix — all only on success. Editing or deleting either kind of operation increments none of them.
- Creating a plant increments `gardening_substrate_component_usage_total` once per component in its initial mix on success; a failed attempt increments nothing.
- `gardening_watering_urgency_ratio` and `gardening_watering_cadence_hours` carry a value for every plant with an available assessment after each completed recomputation, including the one at startup, and no value for a plant with too little watering history to assess.
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
