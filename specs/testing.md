# plant-journal testing

> Standard: Agentic Engineering Standards v1.2.0

Cross-cutting test conventions and gates live in [CONTRIBUTING.md](../CONTRIBUTING.md).

## Service-specific strategy

- Backend unit tests exercise journal orchestration through injected capabilities, including latest-repot synchronization, compensation, accumulated failures, and serialized mutations.
- Persistence seam-integration tests exercise the store against real SQLite with the production migrations, including Nomenclature persistence and operation references, and deliberately malformed rows.
- HTTP seam-integration tests run the Tapir endpoints through its stub interpreter and assert both wire representations and result-specific failures.
- Frontend seam-integration tests exercise HTTP translation through controlled fetch responses; component tests inject the journal client and assert catalog loading, usage-site editing, selection, and label resolution through observable SolidJS behavior.
- There is no separate system-integration tier. Static-file serving alone uses a loopback Netty server because the server boundary is the behavior under test.

## Fixtures & data setup

Persistence tests create uniquely named shared in-memory SQLite databases and apply all Flyway migrations before constructing the store. The production migration supplies initial substrate-component and pesticide rows; tests use their stable UUIDs when verifying references. Tests seed other valid records through focused helpers and insert malformed values through SQL when verifying corruption attribution.

Frontend tests use typed Plant, Operation, Substrate-component, and Pesticide values plus queued client or HTTP results, keeping dates and identifiers deterministic. Inline-editing tests exercise each catalog through the operation form where it is used.

## Integration boundaries

Domain tests replace persistence, time, and identifier capabilities. Persistence tests keep SQLite and migrations real. Backend HTTP tests replace the domain service but keep Tapir codecs and endpoint interpretation real. Frontend component tests replace the journal client, while HTTP-adapter tests keep its decoding and failure mapping real.
