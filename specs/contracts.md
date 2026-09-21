# plant-journal contracts

> Standard: Agentic Engineering Standards v1.2.0

## HTTP API

[`contract/openapi.yaml`](../contract/openapi.yaml) is the authoritative HTTP contract for liveness, the care journal, substrate-component catalog reads and writes, pesticide catalog reads and writes, and pesticide references on care operations. It is generated from the Tapir endpoints; request and response fields are not restated here.

## Error responses

The OpenAPI contract defines each endpoint's status codes and response bodies. Journal and catalog reads and writes surface backend failures explicitly. Operation editing additionally distinguishes a missing operation from an attempted care/repot kind change; catalog editing distinguishes a missing entry. Clients must not treat any of these responses as success.

## Versioning & compatibility

HTTP changes start in the Tapir endpoints and regenerate both the committed OpenAPI document and its generated TypeScript declarations. Contract drift is rejected by `dagger call contract-drift`.

The persistent schema is versioned by the append-only Flyway migrations in [`backend/src/main/resources/db/migration/`](../backend/src/main/resources/db/migration/). Those migrations, rather than a duplicated schema description here, are the database contract.
