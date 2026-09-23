# plant-journal testing

> Standard: Agentic Engineering Standards v1.2.0

Cross-cutting test conventions and gates live in [CONTRIBUTING.md](../CONTRIBUTING.md).

## Service-specific strategy

- Backend component tests exercise complete ports and traits through injected capabilities, including bounded operation reads, attention cadence and refresh behavior, catalog identifier assignment, journal orchestration, multi-page latest-repot synchronization, compensation, accumulated failures, and serialized mutations.
- Persistence seam-integration tests exercise the store against real SQLite with the production migrations, including bounded per-Plant watering samples, page boundaries, next-page lookahead, equal and sub-second timestamp ordering, timestamp normalization during upgrade, catalog behavior, references, and deliberately malformed rows.
- HTTP seam-integration tests run the Tapir endpoints through its stub interpreter and assert attention projections, operation-window defaults and limits, catalog and journal wire representations, and result-specific failures.
- Frontend seam-integration tests exercise attention, catalog, and journal HTTP translation through controlled fetch responses, including malformed attention values. Component tests inject the journal client and cover attention ordering, warning labels, Plant identity changes within indexed cards, history disclosure, pagination, loading, empty, failure, retry, focus, stale-response handling, editing, inline nomenclature editors, and side-sheet transitions.
- Reduced-motion behavior is declarative CSS; component tests do not execute media queries, so its media rule is verified through stylesheet review.
- There is no separate system-integration tier. Static-file serving alone uses a loopback Netty server because the server boundary is the behavior under test.

## Fixtures & data setup

Persistence tests create uniquely named shared in-memory SQLite databases and apply all Flyway migrations before constructing the store. Stable catalog UUIDs and typed nomenclature data keep catalog references explicit. Tests seed valid records through focused helpers and insert malformed values through SQL when verifying corruption attribution.

Frontend tests use typed Plant, Operation, Substrate-component, and Pesticide builders plus queued client or HTTP results, keeping dates and identifiers deterministic.

## Integration boundaries

Domain tests replace persistence, time, and identifier capabilities. Persistence tests keep SQLite, migrations, foreign keys, JSON selection, indexes, and seeded catalogs real. Backend HTTP tests replace the domain services but keep Tapir codecs and endpoint interpretation real. Frontend component tests replace the journal client while keeping attention presentation, editor, and operation-form composition real; HTTP-adapter tests keep attention and catalog decoding and failure mapping real.
