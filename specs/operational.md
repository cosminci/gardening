# plant-journal operations

> Standard: Agentic Engineering Standards v1.2.0

Pipeline, versioning, release, and deployment mechanics live in [ci/specs/operational.md](../ci/specs/operational.md).

## Alerts

There is no runtime monitoring or alerting yet.

## Scaling characteristics

The service is a single process for one household's bounded plant collection. Operation log and edit workflows are serialized within that process, and SQLite coordinates database access with foreign keys enabled and a five-second busy timeout.

## Runtime dependencies

- A writable SQLite file, selected by `GARDENING_DB_PATH` and defaulting to `gardening.db`. Flyway applies the versioned schema before the HTTP server starts; connection or migration failure prevents startup.
- Built frontend assets, selected by `GARDENING_STATIC_DIR` and defaulting to `static`. The backend serves these files from the same Netty server as the API, and the browser calls the journal endpoints on that same origin; unavailable files are returned as not found.
- A bind address and port selected by `GARDENING_HOST` and `GARDENING_PORT`, defaulting to `0.0.0.0:8080`.
