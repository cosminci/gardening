# plant-journal contracts

> Standard: Agentic Engineering Standards v1.2.0

## HTTP API

[`contract/openapi.yaml`](../contract/openapi.yaml) is the authoritative HTTP contract for liveness, plants, operations, substrate-components, and pesticides. The operation-list endpoint is an offset-paginated, bounded read; omitted window parameters return the three recent operations. The catalog endpoints list, create, and edit nomenclatures; plant substrates and care operations reference catalog entries by identifier. The contract is generated from the Tapir endpoints, so field limits and response shapes are not restated here.

## Error responses

The OpenAPI contract defines each endpoint's status codes and response bodies. Invalid operation windows are rejected at the HTTP boundary. Journal and catalog reads and writes surface backend failures explicitly. Operation editing additionally distinguishes a missing operation from an attempted care/repot kind change; catalog editing distinguishes a missing nomenclature. Clients must not treat any of these responses as success.

## Versioning & compatibility

HTTP changes start in the Tapir endpoints and regenerate both the committed OpenAPI document and its generated TypeScript declarations. Contract drift is rejected by `dagger call contract-drift`.

The persistent schema is versioned by the append-only Flyway migrations in [`backend/src/main/resources/db/migration/`](../backend/src/main/resources/db/migration/). Those migrations, rather than a duplicated schema description here, are the database contract.
