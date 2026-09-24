# Pipeline operations

> Standard: Agentic Engineering Standards v1.2.0

## Versioning & release provenance

Release from a clean annotated `vX.Y.Z` tag. The image records the version and commit as OCI provenance; `release-guard` rejects dirty or untagged release trees.

## Publishing

Run full verification before `publish`; it does not run checks. Publish to the private `ghcr.io/cosminci/plant-journal` package using a `write:packages` PAT passed as a Dagger Secret (`--token=env:GITHUB_PERSONAL_PAT`); never put the token in a command argument or a file in the repository.

## Deployment

The NAS pulls the private versioned image with registry authentication and persists SQLite under `/data`. WUD can auto-update from semver tags using a `read:packages` PAT; manual deployment pulls the versioned image and recreates the container. Preserve the data volume across image updates.
