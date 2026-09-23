# plant-journal contracts

> Standard: Agentic Engineering Standards v1.2.0

## HTTP API

[`contract/openapi.yaml`](../contract/openapi.yaml) is the authoritative source for request, response, and error schemas. The service exposes:

| Endpoint | Behavior |
| --- | --- |
| `GET /health` | Check liveness. |
| `GET /plants` | Read current plants by status; active is the default, archived is available on request. |
| `GET /attention` | Read the latest complete watering projection, keyed by plant identity rather than duplicating plant details. |
| `GET /plants/{plantId}/operations` | Read operations with offset pagination; defaults to the three recent operations and allows up to ten per page. |
| `POST /plants/{plantId}/operations` | Log care or repot at a required caller-supplied absolute date. |
| `PUT /operations/{operationId}` | Edit operation details without changing the timestamp or kind. |
| `GET /substrate-components` | List substrate components. |
| `POST /substrate-components` | Add a substrate component. |
| `PUT /substrate-components/{componentId}` | Edit a substrate component. |
| `GET /pesticides` | List pesticides. |
| `POST /pesticides` | Add a pesticide. |
| `PUT /pesticides/{pesticideId}` | Edit a pesticide. |

## Error responses

Malformed, missing, or non-absolute operation dates; unknown plant statuses; invalid operation windows; and malformed journal or catalog input are rejected as client errors. Editing a missing operation or catalog entry is distinct from changing an operation's kind. Journal and catalog failures are reported as server errors without exposing internal exception details. Clients reject unknown attention states and treat failed reads or identity mismatches as load failures, not partial success. Status codes and error bodies are defined by the generated contract.

## Versioning & compatibility

HTTP changes start in the Tapir endpoints and regenerate both the committed OpenAPI document and its generated TypeScript declarations. Contract drift is rejected by `dagger call contract-drift`.

The persistent schema is defined by the [Flyway migration directory](../backend/src/main/resources/db/migration/), not repeated here.
