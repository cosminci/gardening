# Proposed HTTP resource contract

This is the target contract, not the current served API. After implementation, generated OpenAPI owns the wire schemas. `PUT` replaces editable details; `PATCH` changes only plant status.

| Resource | Verb | Path | Request body | Success code and body | Errors |
| --- | --- | --- | --- | --- | --- |
| Plants | `GET` | `/plants?status=active\|archived` | - | 200 `Vector[Plant]`; active by default | 400 unknown status; 500 read failure |
| Plants | `GET` | `/plants/{plantId}` | - | 200 `Plant` (active or archived); **new** | 404 missing; 500 read failure |
| Plants | `GET` | `/plants/archived/count` | - | 200 `{count}` | 500 read failure |
| Plants | `PATCH` | `/plants/{plantId}` | `{"status":"archived"}` | 204 no body | 400 invalid body/status; 404 missing; 409 already archived; 500 write failure |
| Operations | `GET` | `/operations?plantId={plantId}&offset={offset}&pageSize={pageSize}` | - | 200 `OperationPage`; offset 0 and size 3 default, size at most 10; unknown plant yields empty page | 400 missing plantId/invalid window; 500 read failure |
| Operations | `GET` | `/operations/{operationId}` | - | 200 `Operation`; **new** | 404 missing; 500 read failure |
| Operations | `GET` | `/operations/by-plant/{plantId}/date-range` | - | 200 `OperationDateRange` (empty or first/last dates) | 404 missing plant; 500 read failure |
| Operations | `POST` | `/operations` | `{plantId,date,details}` | 201 `{id}`; date is an absolute instant | 400 invalid body/date; 404 missing plant; 409 archived plant; 500 failed write/reference validation |
| Operations | `PUT` | `/operations/{operationId}` | `OperationDetails` | 200 `Operation`; identity, date, and kind unchanged | 400 invalid body; 404 missing; 409 kind mismatch; 500 failed write/reference validation |
| Attention | `GET` | `/attention` | - | 200 `AttentionProjection` (last complete active-plant measurement) | - |
| Substrate components | `GET` | `/substrate/components` | - | 200 `Vector[SubstrateComponent]` | 500 read failure |
| Substrate components | `POST` | `/substrate/components` | `SubstrateComponentData` | 201 `SubstrateComponent` | 400 invalid body; 500 write failure |
| Substrate components | `PUT` | `/substrate/components/{componentId}` | `SubstrateComponentData` | 200 `SubstrateComponent` | 400 invalid ID/body; 404 missing; 500 write failure |
| Pesticides | `GET` | `/pesticides` | - | 200 `Vector[Pesticide]` | 500 read failure |
| Pesticides | `POST` | `/pesticides` | `PesticideData` | 201 `Pesticide` | 400 invalid body; 500 write failure |
| Pesticides | `PUT` | `/pesticides/{pesticideId}` | `PesticideData` | 200 `Pesticide` | 400 invalid ID/body; 404 missing; 500 write failure |

The operation service reports `PlantMissing` and `PlantArchived` separately for POST's 404 and 409, rather than treating them as generic logging failures. Expected 404/409/500 errors use the existing `ApiError` message body; malformed inputs and validation failures retain their existing 400 response behavior. Unknown catalog references still fail an operation without writing it and retain their existing operation-failure classification. Attention refresh failures retain the last complete projection; startup fails if no complete projection exists. No DELETE endpoint or plant/attention creation is introduced.

Changed paths replace the former plant-nested operation collection and date range, archive action, and top-level substrate-component routes. Unchanged paths are retained without parallel aliases.
