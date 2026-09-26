# plant-journal contracts

> Standard: Agentic Engineering Standards v1.2.0

## Contract inventory

| Surface | Authoritative contract |
| --- | --- |
| Entire HTTP API, including operations, payloads, errors, and statuses | Generated [OpenAPI](../contract/openapi.yaml); no parallel endpoint inventory. |
| WebSocket attention feed pushing projections from backend to browser | [Attention feed endpoint](../backend/src/main/scala/gardening/adapters/http/AttentionApi.scala) and [browser feed client](../frontend/src/adapters/ws/WsPlantAttentionFeed.ts); a second protocol surface, not covered by the generated OpenAPI. |
| `GET /metrics` — Prometheus exposition of business, HTTP RED, and process/JVM metrics | [Composition root registration](../backend/src/main/scala/gardening/app/Main.scala); a third protocol surface, not covered by the generated OpenAPI. |
| Plant and operation use cases | [Plants](../backend/src/main/scala/gardening/domain/plants/Plants.scala), [operations](../backend/src/main/scala/gardening/domain/operations/Operations.scala), and [attention monitor](../backend/src/main/scala/gardening/domain/attention/PlantAttentionMonitor.scala). |
| Editable catalog use cases | [Substrate catalog](../backend/src/main/scala/gardening/domain/substrate/SubstrateCatalog.scala) (components and mixes) and [pesticide catalog](../backend/src/main/scala/gardening/domain/pesticide/PesticideCatalog.scala). |
| Storage and external capabilities | [Plant store](../backend/src/main/scala/gardening/domain/plants/PlantStore.scala), [operation store](../backend/src/main/scala/gardening/domain/operations/OperationStore.scala), [photo content store](../backend/src/main/scala/gardening/domain/plants/PhotoContentStore.scala), [attention store](../backend/src/main/scala/gardening/domain/attention/PlantAttentionStore.scala), [substrate store](../backend/src/main/scala/gardening/domain/substrate/SubstrateStore.scala), [pesticide store](../backend/src/main/scala/gardening/domain/pesticide/PesticideStore.scala), [clock](../backend/src/main/scala/gardening/domain/Clock.scala), and [identifier generator](../backend/src/main/scala/gardening/domain/IdGenerator.scala). |
| Metrics capability ports | [Plants](../backend/src/main/scala/gardening/domain/plants/PlantsMetricsApi.scala), [operations](../backend/src/main/scala/gardening/domain/operations/OperationsMetricsApi.scala), [attention monitor](../backend/src/main/scala/gardening/domain/attention/PlantAttentionMonitorMetricsApi.scala), [substrate catalog](../backend/src/main/scala/gardening/domain/substrate/SubstrateCatalogMetricsApi.scala), and [pesticide catalog](../backend/src/main/scala/gardening/domain/pesticide/PesticideCatalogMetricsApi.scala). |
| Browser-facing resource clients | [Plants](../frontend/src/domain/Plant.ts), [operations](../frontend/src/domain/Operation.ts), [attention](../frontend/src/domain/PlantAttention.ts), [substrates](../frontend/src/domain/SubstrateCatalog.ts) (components and mixes), [pesticides](../frontend/src/domain/PesticideCatalog.ts), and [photos](../frontend/src/domain/PlantPhoto.ts). |
| Stored data | [Flyway migrations](../backend/src/main/resources/db/migration/). |
