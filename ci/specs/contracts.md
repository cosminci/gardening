# Pipeline contracts

> Standard: Agentic Engineering Standards v1.2.0

## Contract inventory

The contract with the external CI runner (GitHub Actions) is the `dagger call` invocations the workflows actually make, not the full internal function list — [`.dagger/src/index.ts`](../../.dagger/src/index.ts) is authoritative for that:

| Invocation | Caller | Arguments |
| --- | --- | --- |
| `changed` | `build.yml`, every push/PR | `--base` (defaults to merge-base with `origin/main`). |
| `verify` | `build.yml` (affected only); `release.yml` (`--all`, before a release) | `--base`, or `--all`. |
| `release-version` | `release.yml`, manual dispatch | none — derives a UTC tag, once per dispatched release. |
| `publish` | `release.yml`, manual dispatch | `--tag`, `--token` (a Dagger Secret), `--registry-user`. |

The [runtime image definition](../../.dagger/src/hooks/image.ts) owns image contents, runtime identity, environment, and labels. The application's HTTP surface is defined separately by [OpenAPI](../../contract/openapi.yaml).
