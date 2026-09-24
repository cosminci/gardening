# Pipeline contracts

> Standard: Agentic Engineering Standards v1.2.0

## Contract inventory

The [Dagger module](../../.dagger/src/index.ts) is authoritative for callable arguments, defaults, and results:

| Contract | Responsibility |
| --- | --- |
| `changed` | Classify affected components. |
| `verify` | Check selected components or all components. |
| `backend-check` | Run the backend gate. |
| `frontend-check` | Run the frontend gate. |
| `pipeline-check` | Run the pipeline gate. |
| `contract-drift` | Compare regenerated HTTP contract artifacts to committed ones. |
| `version` | Derive a build version from git. |
| `build-image` | Produce the runtime image. |
| `release-guard` | Reject untagged or dirty release sources. |
| `publish` | Publish a guarded image to GHCR. |
| `deploy` | Produce NAS deployment instructions. |

The [runtime image definition](../../.dagger/src/hooks/image.ts) owns image contents, runtime identity, environment, and labels. The application's HTTP surface is defined separately by [OpenAPI](../../contract/openapi.yaml).
