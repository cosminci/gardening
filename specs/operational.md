# plant-journal operations

> Standard: Agentic Engineering Standards v1.2.0

## Alerts

There is no runtime monitoring or alerting yet.

## Scaling characteristics

The single-household service serializes journal mutations in one process. Opening the cemetery reads archived plants and their histories on demand; its cost grows with archived history, while the unopened garden pays only for an archived count. Attention measurement bounds watering-history reads per active plant.

## Runtime dependencies

- A writable SQLite file (`GARDENING_DB_PATH`, default `gardening.db`). A failed connection or migration prevents startup; back up data before recreating a database migrated with superseded versions.
- Built static assets (`GARDENING_STATIC_DIR`, default `static`) share the API origin; missing assets return not found.
