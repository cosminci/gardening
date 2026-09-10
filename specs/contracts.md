# Contracts — plant-journal

> Standard: Agentic Engineering Standards v1.2.0
>
> Scaffold. Populated by change specs via the SDD skill's Sync & Archive step. The HTTP API is
> single-sourced from the tapir endpoints; its schema lives in the generated OpenAPI document at
> [`contract/openapi.yaml`](../contract/openapi.yaml) rather than being restated here.

## HTTP API

The served API is described by the committed OpenAPI document (`contract/openapi.yaml`), from
which the TypeScript client (`contract/generated/`) is generated. Today it exposes only the
`GET /health` probe.

## Error responses

## Versioning and compatibility
