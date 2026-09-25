# plant-journal operations

> Standard: Agentic Engineering Standards v1.2.0

## Alerts

Each domain service logs its own outcome as a single line: info for a successful mutation or a per-plant watering-level transition (Unavailable/Current/Overdue/RedAlert), error for an unexpected failure (persistence, background recomputation, or startup). Reads and recomputation cycles with no level change produce no line. Read logs directly from the container's output (`docker logs`/`journalctl`); the container caps `json-file` log storage at 10MB × 5 files. There is no aggregation or alerting yet.

## Scaling characteristics

The single-household service serializes journal mutations in one process. Opening the cemetery reads archived plants and their histories on demand; its cost grows with archived history, while the unopened garden pays only for an archived count. Attention measurement bounds watering-history reads per active plant.

## Runtime dependencies

- A writable SQLite file (`GARDENING_DB_PATH`, default `gardening.db`). A failed connection or migration prevents startup; back up data before recreating a database migrated with superseded versions.
- Built static assets (`GARDENING_STATIC_DIR`, default `static`) share the API origin; missing assets return not found.
- [Local development](../CONTRIBUTING.md#local-development) binds both unauthenticated services to workstation loopback. Its journal persists locally; edits never sync back to the NAS.
- Optional SSH refresh uses SQLite's online backup for a consistent snapshot without stopping NAS writes. After validation, atomic replacement leaves either the old or new complete journal on failure or interruption; abandoned snapshots are removed on the next start.
