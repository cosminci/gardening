# Pipeline operations

> Standard: Agentic Engineering Standards v1.2.0

## Versioning & release provenance

- Versions derive from git (contracts.md → Version). Releases are cut from an annotated tag `vX.Y.Z`; `release-guard` refuses to build a release from a dirty tree or an untagged HEAD.
- Every image records provenance as OCI labels (title, description, version, revision = commit sha, created, source). The version and short sha identify the build.

## Publishing

- `publish` pushes `ghcr.io/cosminci/plant-journal:<version>` and `:latest` to the **private** GHCR package (GHCR is the account's own GitHub namespace — no separate registry account; the package is created private on first publish, registry user `cosminci`).
- It takes a `write:packages` GitHub PAT as a Secret. The workstation's `GITHUB_PERSONAL_PAT` carries that scope:

  ```
  dagger call publish --token=env:GITHUB_PERSONAL_PAT
  ```

## Deployment

- The NAS pulls `ghcr.io/cosminci/plant-journal:<version>` (a private image, so the pull is authenticated). Preferred path: WUD auto-update — the image is labelled `wud.watch=true`, and WUD authenticates with a `read:packages` PAT, as it does for lscr.io (the workstation's read-only equivalent is `GITHUB_PERSONAL_RO_PACKAGES_PAT`). Manual path: `ssh nas 'docker pull … && docker compose up -d plant-journal'`.
- The container needs a writable `/data` volume (the SQLite database) and the baked environment (contracts.md → Runtime image). It listens on 8080.
- The Unraid template wiring (volume, port, Tailscale) is a first-deploy detail; record it here when the container is first created on the NAS.

## Local & CI execution

- Verifying and releasing are separate steps. Local / pre-push: `dagger call verify` runs only the affected checks (the pre-push hook, `.githooks/pre-push`, runs it). A release runs `dagger call verify --all` first and only then `dagger call build-image` / `publish` — the build and publish functions do not re-run the checks themselves.
- Images are built for `linux/amd64` (the NAS architecture) by default.
- A GitHub Actions workflow can later run the same `dagger call` steps on push for hands-off deploys; it is not set up yet.
