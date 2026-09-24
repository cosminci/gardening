# plant-journal operations

> Standard: Agentic Engineering Standards v1.2.0

Pipeline, versioning, release, and deployment mechanics live in [ci/specs/operational.md](../ci/specs/operational.md).

## Alerts

There is no runtime monitoring or alerting yet.

## Scaling characteristics

The service is a single process for one household's bounded plant collection. Archive, operation log, and edit workflows are serialized within that process, and SQLite coordinates database access with foreign keys enabled and a five-second busy timeout. Each attention refresh reads active plant identities and up to 20 watering dates per plant through indexed operation seeks. Plant reads filter by status before decoding, so garden loading does not read archived details. The garden obtains an archived count through an aggregate query without loading archived cards; opening the cemetery loads archived plants on demand, then reads recent operations and all recorded date values per plant to determine the care range. Cemetery loading grows with archived plants and their operation histories, while the unopened garden avoids that cost. A startup attention-read failure prevents serving, while a later refresh failure retains the previous complete projection.

## Runtime dependencies

- A writable SQLite file, selected by `GARDENING_DB_PATH` and defaulting to `gardening.db`. Flyway applies the single pre-deployment baseline before the HTTP server starts; connection or migration failure prevents startup. Local development databases already migrated with superseded versions need recreation before use; preserve any wanted data first.
- Built frontend assets, selected by `GARDENING_STATIC_DIR` and defaulting to `static`. The backend serves these files from the same Netty server as the API, and the browser calls the journal endpoints on that same origin; unavailable files are returned as not found.
- A bind address and port selected by `GARDENING_HOST` and `GARDENING_PORT`, defaulting to `0.0.0.0:8080`.
