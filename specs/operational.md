# plant-journal operations

> Standard: Agentic Engineering Standards v1.2.0

Pipeline, versioning, release, and deployment mechanics live in [ci/specs/operational.md](../ci/specs/operational.md).

## Alerts

There is no runtime monitoring or alerting yet.

## Scaling characteristics

The service is a single process for one household's bounded plant collection. Operation log and edit workflows are serialized within that process, and SQLite coordinates database access with foreign keys enabled and a five-second busy timeout. Each attention refresh reads active plant identities and up to 20 watering dates per plant through indexed operation seeks. Separate plant reads validate every stored plant before status filtering. A startup attention-read failure prevents serving, while a later refresh failure retains the previous complete projection.

## Runtime dependencies

- A writable SQLite file, selected by `GARDENING_DB_PATH` and defaulting to `gardening.db`. Flyway applies the single pre-deployment baseline before the HTTP server starts; connection or migration failure prevents startup. Local development databases already migrated with superseded versions need recreation before use; preserve any wanted data first.
- Built frontend assets, selected by `GARDENING_STATIC_DIR` and defaulting to `static`. The backend serves these files from the same Netty server as the API, and the browser calls the journal endpoints on that same origin; unavailable files are returned as not found.
- A bind address and port selected by `GARDENING_HOST` and `GARDENING_PORT`, defaulting to `0.0.0.0:8080`.
