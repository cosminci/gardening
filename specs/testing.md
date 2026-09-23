# plant-journal testing

> Standard: Agentic Engineering Standards v1.2.0

Cross-cutting test conventions and gates live in [CONTRIBUTING.md](../CONTRIBUTING.md).

## Service-specific strategy

- Backend component tests exercise complete ports and traits through injected capabilities, including caller-supplied operation dates, immutable edit dates, bounded operation reads, identity-only attention and refresh behavior, catalog identifier assignment, latest versus historical repot synchronization, compensation, accumulated failures, and serialized mutations.
- Persistence seam-integration tests exercise the store against real SQLite with the current baseline, including active and archived plant reads, validation before filtering, bounded per-Plant watering dates, page boundaries, next-page lookahead, equal and sub-second timestamp ordering, catalog references, and deliberately malformed rows.
- HTTP seam-integration tests run the Tapir endpoints through its stub interpreter and assert required absolute logging dates, status-filtered reads and invalid statuses, identity-only attention projections, operation-window defaults and limits, catalog and journal wire representations, and result-specific failures.
- Frontend seam-integration tests exercise attention, catalog, and journal HTTP translation through controlled fetch responses, including malformed attention values. Component tests inject the journal client and cover local-minute entry and validation, recent and historical date display, fresh plant and attention identity matching after writes, attention ordering and accessible warnings, history disclosure and pagination, loading and retry, focus, editing, inline nomenclature editors, and side-sheet transitions.
- Reduced-motion behavior is declarative CSS; component tests do not execute media queries, so its media rule is verified through stylesheet review.
- There is no separate system-integration tier. Static-file serving alone uses a loopback Netty server because the server boundary is the behavior under test.

## Fixtures & data setup

Persistence tests create uniquely named shared in-memory SQLite databases and apply the current Flyway baseline before constructing the store. Stable catalog UUIDs and typed nomenclature data keep catalog references explicit. Tests seed active and archived plants and valid operations through focused helpers, and insert malformed values through SQL when verifying corruption attribution.

Frontend tests keep domain values and short sequences explicit in the owning use case, with deterministic local dates and identifiers. Suite-local client builders own queued outcomes and captured calls without hiding domain setup behind configurable fixture factories.

## Integration boundaries

Domain tests replace persistence, attention time, and identifier capabilities. Persistence tests keep SQLite, the baseline migration, foreign keys, JSON selection, indexes, and seeded catalogs real. Backend HTTP tests replace the domain services but keep Tapir codecs and endpoint interpretation real. Frontend component tests replace the journal client while keeping attention presentation, editor, and operation-form composition real; HTTP-adapter tests keep plant and attention decoding and failure mapping real.
