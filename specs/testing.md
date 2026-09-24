# plant-journal testing

> Standard: Agentic Engineering Standards v1.2.0

Cross-cutting test conventions and gates live in [CONTRIBUTING.md](../CONTRIBUTING.md).

## Service-specific strategy

- Backend component tests exercise the shared plant/operation journal, attention monitor, and independent substrate-component and pesticide catalogs through their ports, including operation dates, repot synchronization, compensation, full attention refresh after archiving, catalog identifier assignment, and serialized mutations.
- Persistence seam-integration tests exercise the journal/attention SQLite adapter and the separate substrate-component and pesticide adapters against real SQLite. They cover status-filtered reads, count-only reads, archive transitions, bounded watering samples, operation pagination and date ordering, catalog references, and malformed stored data.
- Resource-owned HTTP seam-integration tests drive plant, operation, attention, substrate-component, and pesticide endpoints through Tapir's stub interpreter. Plant cases include RFC 6902 archival patches, rejected documents and media types, and refresh failure; operation cases include filtered pages, date ranges, logging and editing; catalog cases include each resource's wire and error behavior.
- Frontend seam-integration tests exercise separate plant, operation, attention, substrate-component, and pesticide HTTP clients through controlled fetch responses, including malformed attention values and failed archive/count/date reads. Component tests inject the separate resource clients and cover local-minute entry and validation, attention matching and ordering, history pagination, loading and retry, focus, editing, inline nomenclature editors, and lazy cemetery browsing and archive confirmation.
- Reduced-motion behavior is declarative CSS; component tests do not execute media queries, so its media rule is verified through stylesheet review.
- There is no separate system-integration tier. Static-file serving alone uses a loopback Netty server because the server boundary is the behavior under test.

## Fixtures & data setup

Persistence tests create uniquely named shared in-memory SQLite databases and apply the current Flyway baseline before constructing each adapter. Stable catalog UUIDs and typed nomenclature data keep catalog references explicit. Tests seed active and archived plants and valid operations through focused helpers, and insert malformed values through SQL when verifying corruption attribution or that active reads do not decode archived rows. Mixed-precision and malformed-middle operation dates probe the recorded care range.

Frontend tests keep domain values and short sequences explicit in the owning use case, with deterministic local dates and identifiers. Suite-local client builders own queued outcomes and captured calls without hiding domain setup behind configurable fixture factories.

## Integration boundaries

Domain tests replace journal, attention, and catalog persistence ports, time, and identifier capabilities. Persistence tests keep SQLite, the baseline migration, foreign keys, JSON selection, indexes, and seeded catalogs real. Backend HTTP tests replace the owning domain service but keep each resource's Tapir codecs and endpoint interpretation real. Frontend component tests replace the plant, operation, attention, and catalog clients while keeping attention presentation, editor, and operation-form composition real; HTTP-client tests keep each resource's decoding and failure mapping real.
