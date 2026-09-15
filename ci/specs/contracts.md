# Pipeline contracts

> Standard: Agentic Engineering Standards v1.2.0

The module is invoked as `dagger call <function>`. Every function that reads the repository takes a `source` directory argument that defaults to the repo root, with heavy and generated trees ignored.

| Function | Arguments | Result | Purpose |
| --- | --- | --- | --- |
| `changed` | `base?` | components (comma-separated) or `none` | Affected components for a change set. |
| `verify` | `base?`, `all?` | summary string | Run affected checks (or all); fails if any check fails. |
| `backend-check` | — | check output | Backend gate in a JDK 25 + sbt container. |
| `frontend-check` | — | check output | Frontend gate in a Node container. |
| `pipeline-check` | — | check output | The pipeline's own Vitest suite in a Node container. |
| `contract-drift` | — | check output | Fail if the regenerated contract differs from the committed one. |
| `version` | — | version string | Derived from `git describe --tags --always --dirty`. |
| `build-image` | `platform?` | Container | The slim non-root runtime image (default `linux/amd64`). Does not verify. |
| `release-guard` | — | ok string / error | Refuse a dirty tree or an untagged HEAD. |
| `publish` | `token` (Secret), `platform?` | published references | Guarded release image pushed to GHCR. |
| `deploy` | `version` | command string | NAS pull / recreate instructions. |

## Version

An exact tag `vX.Y.Z` yields `X.Y.Z`; otherwise the `git describe` form `X.Y.Z-<n>-g<sha>` (or a bare short sha when untagged), leading `v` stripped. A dirty tree keeps git's `-dirty` suffix.

## Runtime image

- Base: `debian:stable-slim` (a minimal glibc base). The backend bundles its own jlink runtime, so no JRE is installed in the image; only libc is needed for that runtime and the SQLite native library.
- Built for `linux/amd64` by default (the NAS architecture); pass a different `platform` to change it.
- Non-root user `1000:1000`; exposes 8080; entrypoint `/app/bin/gardening-backend`; the built frontend at `/app/static`; a writable `/data` (owned by 1000) for SQLite. Ownership is set during the copy, so there is no duplicate chown layer.
- Environment baked in: `GARDENING_APP_VERSION`, `GARDENING_STATIC_DIR`, `GARDENING_DB_PATH`, `GARDENING_PORT`. The server binds `GARDENING_HOST` (default `0.0.0.0`).
- OCI labels: `org.opencontainers.image.{title,description,version,revision,created,source}`.
- Auto-update labels: `wud.watch=true` and `wud.tag.include` (semver tags only).
- Published as `plant-journal:<version>` and `plant-journal:<sha>`.

## Publish

Pushes `ghcr.io/cosminci/plant-journal:<version>` and `:latest` to the private package. Authentication is a GitHub PAT with `write:packages`, provided by the caller as a Secret — the workstation's `GITHUB_PERSONAL_PAT` carries that scope (`--token=env:GITHUB_PERSONAL_PAT`). `publish` runs `releaseGuard` and builds the image, but does not run the checks — run `verify --all` before publishing.
