# plant-journal contracts

> Standard: Agentic Engineering Standards v1.2.0

## HTTP API

[`contract/openapi.yaml`](../contract/openapi.yaml) is the authoritative source for request, response, and error schemas. The service exposes:

| Endpoint | Behavior |
| --- | --- |
| `GET /health` | Check liveness. |
| `GET /plants` | Read current plants by status; active is the default, archived is available on request. |
| `GET /plants/archived/count` | Count archived plants without reading their details or history. |
| `PATCH /plants/{plantId}` | Permanently archive an active plant using an RFC 6902 JSON Patch. |
| `GET /attention` | Read the latest complete watering projection, keyed by plant identity rather than duplicating plant details. |
| `GET /operations?plantId={plantId}` | Read operations with offset pagination; defaults to the three recent operations and allows up to ten per page. |
| `GET /operations/date-range?plantId={plantId}` | Read the earliest and latest recorded operation dates, or an empty range. |
| `POST /operations` | Log care or repot for an active plant at a required caller-supplied absolute date and plant identity. |
| `PUT /operations/{operationId}` | Edit operation details without changing the timestamp or kind. |
| `GET /substrate/components` | List substrate components. |
| `POST /substrate/components` | Add a substrate component. |
| `PUT /substrate/components/{componentId}` | Edit a substrate component. |
| `GET /pesticides` | List pesticides. |
| `POST /pesticides` | Add a pesticide. |
| `PUT /pesticides/{pesticideId}` | Edit a pesticide. |

## Error responses

Malformed, missing, or non-absolute operation dates; unknown plant statuses; invalid operation windows; and malformed journal or catalog input are rejected as client errors. Unsupported or malformed plant patch documents return 400, while a patch with an unsupported media type returns 415. Archiving an unknown plant or requesting its date range returns not found; repeated archiving and logging a new operation for an archived plant return conflict. A failed archive or count/date-range read reports a server error without reporting success. Editing a missing operation or catalog entry is distinct from changing an operation's kind. Other journal and catalog failures are also reported as server errors without exposing internal exception details. Clients reject unknown attention states and treat failed reads or identity mismatches as load failures, not partial success. Status codes and error bodies are defined by the generated contract.

## Versioning & compatibility

HTTP changes start in the Tapir endpoints and regenerate both the committed OpenAPI document and its generated TypeScript declarations. Contract drift is rejected by `dagger call contract-drift`.

The resource-oriented paths replace the former nested operation, archive-action, and substrate-component paths without compatibility aliases. The bundled browser clients and generated contract change with the served API.

The persistent schema is defined by the [Flyway migration directory](../backend/src/main/resources/db/migration/), not repeated here.
