# Versioned GHCR releases and Unraid deployment

> Standard: [Agentic Engineering Standards](https://github.com/Adobe-AIFoundations/agentic-workflow-standards) v1.2.0.
> Lifetime: open from creation through implementation, archived in the [sync docs & archive step](../../agentic-workflows.md#a-structured-development-skills).

**Date:** 2026-09-24

Publish versioned plant-journal images and let the operator manage the NAS installation directly in Unraid.

## What & Why

- The pipeline builds the runtime image and the trial proved private registry access, but the existing proposal adds a `latest` alias and scripted NAS preparation and recovery, while older operational guidance still prefers WUD auto-update; the operator wants none of those.
- Stable versions from merged main, beginning with `v1.0.0`, are published by a dedicated check. The operator imports an Unraid template once, then edits its selected version when WUD reports an update.

## Domain / Design Notes

- Publishing owns version provenance and registry delivery. Unraid and the operator own image selection, container lifecycle, persistent data, and recovery from existing backups; WUD only reports availability.

## Invariants

- Full verification precedes publication; packaging and publishing do not silently substitute for verification.
- The runtime serves the frontend and API together as a non-root process.
- The unauthenticated service remains reachable only on the household LAN and tailnet. Credentials and journal data never enter the published image or repository.

## Tradeoffs Accepted

- Manual recovery from an existing periodic backup may lose entries recorded after it; a previous image alone may not read a database migrated by a newer version.

## Acceptance Criteria

- Annotated `vMAJOR.MINOR.PATCH` tags on merged main select stable releases (major for incompatible changes, minor for backward-compatible features, patch for fixes). A dedicated tag-push publishing check verifies the system and refuses invalid, prerelease, unmerged, or already-published versions before publication.
- Authenticated publication provides a private NAS-compatible GHCR image under its stable version tag with commit/version provenance and an immutable digest. No `latest` alias is maintained; missing or invalid credentials fail visibly without exposing them.
- The operator imports the Unraid template once, supplies read-only registry credentials, selects an existing published version, and applies it to run one container with persistent writable journal data. Unraid reports pull and startup failures; the operator verifies that the service reports the selected version and is reachable on the LAN and tailnet, not the public internet.
- WUD is configured once on the NAS to report newer stable versions of this service without updating it; no WUD configuration is persisted in the repository. The operator edits the existing Unraid template's version and applies it when ready; journal entries remain available after replacement. Other containers retain their existing update behavior.
- The already-published trial remains runnable and its version checkable until the operator moves to stable. For incompatible images, the operator stops the service and manually restores a compatible existing Unraid backup before applying a selected version.

## Doc Sync

- `ci/specs/design.md` — Processing rules, Edge cases, and Invariants for stable-only publication and operator-owned rollout.
- `ci/specs/contracts.md` — Version, Publish, and Runtime image contracts for version-only tags and provenance.
- `ci/specs/testing.md` — Traceability and Pipeline validation for the publishing check and manual deployment.
- `ci/specs/operational.md` — Versioning & release provenance, Publishing, and Deployment for the release check, one-time Unraid/WUD setup, manual updates, and recovery.
- `specs/operational.md` — Deployment topology for the running NAS service, volume, network, and health.
- `CONTRIBUTING.md` — Working in the repo: release commands.
