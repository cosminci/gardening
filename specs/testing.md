# plant-journal testing

> Standard: Agentic Engineering Standards v1.2.0

## Strategy

- Prove repot ordering and failed-write compensation through the journal boundary; prove persisted ordering separately with real SQLite. Together these establish the orchestration and storage semantics without tying service tests to database mechanics.
- Exercise stored-data corruption, status-filtered reads, and pagination with real SQLite. Substituting storage cannot establish whether decoding and query boundaries isolate malformed rows or preserve ordering.
- Exercise resource-specific HTTP translation through the actual endpoint interpretation, and browser reconciliation with independently changing journal and attention responses. Neither the domain service nor the generated schema alone proves that failures and stale projections cannot look like successful current data.

## Validation beyond isolated tests

Reduced-motion presentation depends on a browser media query rather than application logic; inspect the rendered behavior under that preference.
