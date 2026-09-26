# plant-journal operations

> Standard: Agentic Engineering Standards v1.2.0

## Alerts

Each domain service logs its own outcome as a single line: info for a successful mutation or a per-plant watering-level transition (Unavailable/Current/Overdue/RedAlert), error for an unexpected failure (persistence, background recomputation, or startup). Reads and recomputation cycles with no level change produce no line. Read logs directly from the container's output (`docker logs`/`journalctl`); the container caps `json-file` log storage at 10MB × 5 files. `GET /metrics` (see Metrics below) is a second operational signal alongside these logs; there is still no aggregation or alerting configured on either.

## Metrics

`GET /metrics` exposes Prometheus text format from one process-wide registry. The NAS's Victoria Metrics scrapes it directly (no push gateway), via a scrape-config addition maintained outside this repo, and visualizes it through the committed Grafana dashboard, kept in sync by `grafana/push.sh` copying the dashboard JSON into Grafana's file-provisioning directory.

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

- `gardening_attention_feed_connections` is heartbeat/TTL-based (`ConnectionHeartbeats`), not incremented on open and decremented on close, so a missed close can't leak it: it's recomputed as "connections heartbeated within the last 5s" on every scrape, so a missed event self-corrects on the next heartbeat instead of leaking forever. This is also the only reliable way to track the attention feed's concurrency — see below.
- The two storage gauges walk `GARDENING_DB_PATH`'s file and `GARDENING_PHOTOS_DIR`'s tree fresh on every scrape; neither is cached or incremented.

**HTTP RED** (`PrometheusMetrics(...).addRequestsTotal().addRequestsDuration()`, namespace `gardening`):

| Metric | Type | Labels |
| --- | --- | --- |
| `gardening_request_total` | Counter | `path`, `method`, `status` |
| `gardening_request_duration_seconds` | Histogram | `path`, `method`, `status` |

`PrometheusMetrics.default` also offers a `request_active` gauge; it's deliberately not registered. tapir's `MetricsRequestInterceptor` increments it unconditionally for every request in its top-level interceptor, before `ignoreEndpoints` is even consulted, but only decrements it via completion hooks (`onResponseBody`/`onException`/`onInterceptorResponse`/`onDecodeFailure`) that `ignoreEndpoints` skips entirely for excluded endpoints. Any endpoint on that list leaks the gauge permanently — which includes the attention feed (excluded because its long-lived WebSocket connections don't belong in RED request-duration/count stats) and tapir's own metrics endpoint (excluded unconditionally by the library itself). Verified locally: a feed connection opened and closed left a permanent +1, and every single `/metrics` scrape left another, while ordinary endpoints incremented and decremented correctly. `gardening_attention_feed_connections` is the gauge that actually reflects feed concurrency.

**Process/JVM USE** — `prometheus-metrics-instrumentation-jvm`'s standard series (`process_cpu_seconds_total`, `jvm_memory_used_bytes`, GC pause, thread count, etc.), registered at startup before the server accepts requests. cAdvisor remains the source for container-level CPU/memory/network, scraped independently.

## Scaling characteristics

The single-household service serializes journal mutations in one process. Opening the cemetery reads archived plants and their histories on demand; its cost grows with archived history, while the unopened garden pays only for an archived count. Attention measurement bounds watering-history reads per active plant.

## Runtime dependencies

- A writable SQLite file (`GARDENING_DB_PATH`, default `gardening.db`). A failed connection or migration prevents startup; back up data before recreating a database migrated with superseded versions.
- A writable photo content directory, separate from the SQLite file: the NAS array in production, a local directory in local development. Photo volume never grows the SQLite file or its backup path.
- Built static assets (`GARDENING_STATIC_DIR`, default `static`) share the API origin; missing assets return not found.
- [Local development](../CONTRIBUTING.md#local-development) binds both unauthenticated services to workstation loopback. Its journal persists locally; edits never sync back to the NAS. `--refresh` pulls only the SQLite journal from the NAS; local photo content is separate and is never seeded or synced by it.
- Optional SSH refresh uses SQLite's online backup for a consistent snapshot without stopping NAS writes. After validation, atomic replacement leaves either the old or new complete journal on failure or interruption; abandoned snapshots are removed on the next start.
- `GET /metrics` (Prometheus exposition, see [Metrics](#metrics) above) is always exposed; nothing in the app depends on anything scraping it, and startup/behavior are identical whether or not a scraper exists. The same Victoria Metrics/Grafana pair can run standalone for local development, pointed at the local backend's `/metrics`, with no code or config change to the app itself.

## Deployment topology

Single Docker container on the household NAS (Unraid) — no orchestration, no replicas — installed from the committed [Unraid template](../unraid/plant-journal.xml).

- **Image** — private `ghcr.io/cosminci/plant-journal`; restarts unless stopped; `json-file` logs capped at 10MB × 5 files.
- **Network** — the configured WebUI port maps to the container's `8080`; LAN and Tailscale only, never the public router (see [Exposure](../CONTRIBUTING.md#working-in-the-repo)).
- **Data** — the SQLite journal lives under the bind-mounted `/data` (default `/mnt/user/appdata/plant-journal`), owned by UID/GID `1000:1000` and included in Unraid's periodic Appdata Backup.

Step-by-step install/update/recovery is the operator runbook in [`unraid/README.md`](../unraid/README.md); publish/deploy mechanics are owned by [`ci/specs/operational.md`](../ci/specs/operational.md) — this section only records where and how the running service is configured, not how to change it.
