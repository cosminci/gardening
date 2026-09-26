# plant-journal operations

> Standard: Agentic Engineering Standards v1.2.0

## Alerts

Each domain service logs its own outcome as a single line: info for a successful mutation or a per-plant watering-level transition (Unavailable/Current/Overdue/RedAlert), error for an unexpected failure (persistence, background recomputation, or startup). Reads and recomputation cycles with no level change produce no line. Read logs directly from the container's output (`docker logs`/`journalctl`); the container caps `json-file` log storage at 10MB × 5 files. There is no aggregation or alerting yet.

## Metrics

`GET /metrics` exposes Prometheus text format from one process-wide registry, scraped by the NAS's Victoria Metrics and visualized by the committed Grafana dashboard.

**Business** (domain-owned, via `using`-threaded `*MetricsApi` ports):

| Metric | Type | Labels |
| --- | --- | --- |
| `gardening_journal_plants` | Gauge | `status` |
| `gardening_attention_urgency_ratio` | Gauge | `plant` |
| `gardening_attention_watering_cadence_seconds` | Gauge | `plant` |
| `gardening_journal_operations_total` | Counter | `action` |
| `gardening_journal_moisture_readings_total` | Counter | `level` |
| `gardening_journal_repots_total` | Counter | `plant` |
| `gardening_journal_substrate_component_usage_total` | Counter | `component` |
| `gardening_journal_pesticide_applications_total` | Counter | `pesticide` |

`gardening_journal_plant_info`, `gardening_journal_substrate_component_info`, and `gardening_journal_pesticide_info` (Gauge, always `1`, labeled `<entity>` + `name`) each join a display name onto the id-labeled series above. Every one is rebuilt wholesale from the latest catalog read, not incremented per rename — a Grafana `group_left` join always sees the current name for an id, whereas a `{id, name}` counter/gauge would accumulate one ambiguous stale series per past name.

**Connections and storage:**

| Metric | Type |
| --- | --- |
| `gardening_attention_feed_connections` | Gauge |
| `gardening_storage_db_bytes` | Gauge |
| `gardening_storage_photos_bytes` | Gauge |

- `gardening_attention_feed_connections` is heartbeat/TTL-based (`ConnectionHeartbeats`), not incremented on open and decremented on close: the attention feed is a WebSocket upgrade, which never reaches tapir's completion hooks, so an edge-triggered counter would drift on any missed close — the same failure mode observed below in tapir's own request tracking. It's recomputed as "connections heartbeated within the last 5s" on every scrape, so a missed event self-corrects on the next heartbeat instead of leaking forever.
- The two storage gauges walk `GARDENING_DB_PATH`'s file and `GARDENING_PHOTOS_DIR`'s tree fresh on every scrape; neither is cached or incremented.

**HTTP RED** (tapir's default metric set, `PrometheusMetrics.default`, namespace `gardening`):

| Metric | Type | Labels |
| --- | --- | --- |
| `gardening_request_total` | Counter | `path`, `method`, `status` |
| `gardening_request_active` | Gauge | `path`, `method` |
| `gardening_request_duration_seconds` | Histogram | `path`, `method`, `status` |

The attention feed's endpoint is excluded from this interceptor (`metricsInterceptor(Seq(AttentionApi.attentionFeedEndpoint))`): a WebSocket upgrade never fires tapir's completion hooks (`onResponseBody`/`onException`/`onInterceptorResponse`/`onDecodeFailure`), so without the exclusion `gardening_request_active` only ever incremented for that endpoint and never came back down.

**Process/JVM USE** — `prometheus-metrics-instrumentation-jvm`'s standard series (`process_cpu_seconds_total`, `jvm_memory_used_bytes`, GC pause, thread count, etc.), registered at startup before the server accepts requests. cAdvisor remains the source for container-level CPU/memory/network, scraped independently.

## Scaling characteristics

The single-household service serializes journal mutations in one process. Opening the cemetery reads archived plants and their histories on demand; its cost grows with archived history, while the unopened garden pays only for an archived count. Attention measurement bounds watering-history reads per active plant.

## Runtime dependencies

- A writable SQLite file (`GARDENING_DB_PATH`, default `gardening.db`). A failed connection or migration prevents startup; back up data before recreating a database migrated with superseded versions.
- A writable photo content directory, separate from the SQLite file: the NAS array in production, a local directory in local development. Photo volume never grows the SQLite file or its backup path.
- Built static assets (`GARDENING_STATIC_DIR`, default `static`) share the API origin; missing assets return not found.
- [Local development](../CONTRIBUTING.md#local-development) binds both unauthenticated services to workstation loopback. Its journal persists locally; edits never sync back to the NAS.
- Optional SSH refresh uses SQLite's online backup for a consistent snapshot without stopping NAS writes. After validation, atomic replacement leaves either the old or new complete journal on failure or interruption; abandoned snapshots are removed on the next start.
