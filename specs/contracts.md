# plant-journal contracts

> Standard: Agentic Engineering Standards v1.2.0

## Contract inventory

| Surface | Authoritative contract |
| --- | --- |
| Entire HTTP API, including operations, payloads, errors, and statuses | Generated [OpenAPI](../contract/openapi.yaml); no parallel endpoint inventory. |
| WebSocket attention feed pushing projections from backend to browser | [Attention feed endpoint](../backend/src/main/scala/gardening/adapters/http/AttentionApi.scala) and [browser feed client](../frontend/src/adapters/ws/WsPlantAttentionFeed.ts); a second protocol surface, not covered by the generated OpenAPI. |
| `GET /metrics` — Prometheus exposition of business, HTTP RED, and process/JVM metrics | [Composition root registration](../backend/src/main/scala/gardening/app/Main.scala); a third protocol surface, not covered by the generated OpenAPI. |
| Stored data | [Flyway migrations](../backend/src/main/resources/db/migration/). |
