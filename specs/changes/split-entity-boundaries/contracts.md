# Proposed HTTP resource paths

Only paths change. Each route retains its existing verb, path/query inputs, request body, response body/status, and error behavior after the archived-plants prerequisite merges. Plant-scoped operations keep `plantId` in the path; archive remains a bodyless POST.

| Resource | Verb | Existing path | Proposed path |
| --- | --- | --- | --- |
| Plants | `GET` | `/plants?status=active\|archived` | `/plants?status=active\|archived` |
| Plants | `GET` | `/plants/archived/count` | `/plants/archived/count` |
| Plants | `POST` | `/plants/{plantId}/archive` | `/plants/{plantId}/archivals` |
| Operations | `GET` | `/plants/{plantId}/operations?offset={offset}&pageSize={pageSize}` | `/operations/plants/{plantId}?offset={offset}&pageSize={pageSize}` |
| Operations | `GET` | `/plants/{plantId}/operation-date-range` | `/operations/plants/{plantId}/date-range` |
| Operations | `POST` | `/plants/{plantId}/operations` | `/operations/plants/{plantId}` |
| Operations | `PUT` | `/operations/{operationId}` | `/operations/{operationId}` |
| Attention | `GET` | `/attention` | `/attention` |
| Substrate components | `GET` | `/substrate-components` | `/substrate/components` |
| Substrate components | `POST` | `/substrate-components` | `/substrate/components` |
| Substrate components | `PUT` | `/substrate-components/{componentId}` | `/substrate/components/{componentId}` |
| Pesticides | `GET` | `/pesticides` | `/pesticides` |
| Pesticides | `POST` | `/pesticides` | `/pesticides` |
| Pesticides | `PUT` | `/pesticides/{pesticideId}` | `/pesticides/{pesticideId}` |

No new read, write, or delete operation is introduced. Superseded paths are removed rather than kept as aliases; after implementation the generated OpenAPI contract is authoritative for schemas.
