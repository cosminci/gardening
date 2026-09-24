# HTTP resource path transition

Journal routes use plant identity as a filter or relationship, never as a child of operations. Archiving uses `application/json-patch+json` (RFC 6902) to replace the plant's status. Other routes retain their existing verb, inputs, response body/status, and error behavior.

| Resource | Verb | Existing path | Proposed path |
| --- | --- | --- | --- |
| Plants | `GET` | `/plants?status=active\|archived` | `/plants?status=active\|archived` |
| Plants | `GET` | `/plants/archived/count` | `/plants/archived/count` |
| Plants | `POST` → `PATCH` | `/plants/{plantId}/archive` (empty body) | `/plants/{plantId}` with `[{"op":"replace","path":"/details/status","value":"archived"}]` |
| Operations | `GET` | `/plants/{plantId}/operations?offset={offset}&pageSize={pageSize}` | `/operations?plantId={plantId}&offset={offset}&pageSize={pageSize}` |
| Operations | `GET` | `/plants/{plantId}/operation-date-range` | `/operations/date-range?plantId={plantId}` |
| Operations | `POST` | `/plants/{plantId}/operations` with `{date,details}` | `/operations` with `{plantId,date,details}` |
| Operations | `PUT` | `/operations/{operationId}` | `/operations/{operationId}` |
| Attention | `GET` | `/attention` | `/attention` |
| Substrate components | `GET` | `/substrate-components` | `/substrate/components` |
| Substrate components | `POST` | `/substrate-components` | `/substrate/components` |
| Substrate components | `PUT` | `/substrate-components/{componentId}` | `/substrate/components/{componentId}` |
| Pesticides | `GET` | `/pesticides` | `/pesticides` |
| Pesticides | `POST` | `/pesticides` | `/pesticides` |
| Pesticides | `PUT` | `/pesticides/{pesticideId}` | `/pesticides/{pesticideId}` |

No new read, write, or delete operation is introduced. The patch accepts only the archival transition; unsupported or malformed documents return 400, and unsupported media types return 415, before reaching the journal. Superseded paths are removed rather than kept as aliases; after implementation the generated OpenAPI contract is authoritative for schemas.
