# Document operation-edit errors

> Standard: Agentic Engineering Standards v1.2.0
>
> Lifetime: open through implementation, then archived during doc sync.

## Classification

Investigation.

## What and why

Editing an operation already reports two expected domain outcomes over HTTP:

- `404 Not Found` when the operation does not exist.
- `409 Conflict` when an edit attempts to change the operation type.

The generated OpenAPI document does not advertise either response, and represents the known internal
failure as an unspecified default error. Consumers therefore cannot see the complete stable response
set even though the server and frontend already distinguish it.

The root cause is the generic error output shared by all journal endpoints: the edit handler selects
`404` and `409` at runtime, but its endpoint description does not enumerate those variants for
OpenAPI generation. The correction must specialize the edit endpoint without changing the generated
contract for the other journal endpoints.

The following hypotheses were rejected:

- The server does not return these statuses: HTTP seam tests already prove both responses.
- The frontend cannot handle them: its generated-client adapter already maps both statuses.
- The generated file is stale: regeneration reproduces the omission from the endpoint definition.

## New behavior

The generated API contract explicitly lists the existing `404`, `409`, and `500` edit-operation
responses, including their JSON error body. Runtime response selection and messages remain unchanged.

## Acceptance criteria

- `PUT /operations/{operationId}` documents `404` with the operation-not-found error.
- The same endpoint documents `409` with the immutable-operation-type conflict error.
- The same endpoint documents `500` with the internal edit-failure error.
- Existing success and malformed-request responses remain documented.
- Runtime responses and all other endpoints remain unchanged.
- A generated frontend client continues to distinguish missing-operation and type-conflict outcomes.

## Invariants

- Tapir remains the single source of truth for `contract/openapi.yaml`.
- The generated contract is never edited manually.
- Internal failure details are not exposed in HTTP responses.

## Doc Sync

None. `contract/openapi.yaml` is the generated source of truth. The still-open care-journal change
retains ownership of updating `specs/contracts.md` to point at the API contract and database schema
without restating either.

## Out of scope

- Specializing error documentation for the other journal endpoints.
