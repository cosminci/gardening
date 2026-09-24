# Timestamped GHCR releases and Unraid deployment

> Standard: [Agentic Engineering Standards](https://github.com/Adobe-AIFoundations/agentic-workflow-standards) v1.2.0.
> Lifetime: open from creation through implementation, archived in the [sync docs & archive step](../../agentic-workflows.md#a-structured-development-skills).

**Date:** 2026-09-24

Build only affected components during review; publish timestamped plant-journal images on demand and let the operator manage the NAS installation in Unraid.

## What & Why

- Pull requests need automatic checks scoped to changed components. Publishing should instead be an explicit operator action on merged main, with full verification before a private image is released.
- Release identities are UTC date-and-time stamps in `YYYY.M.D-THHMMSS` form, not semantic versions or Git release tags. The operator imports an Unraid template once, then edits its image tag when WUD reports an update.

## Domain / Design Notes

- Review checks identify the affected backend, frontend, and pipeline components; contract changes affect both app components. Publication uses a separate full-system gate.
- Publishing owns image provenance and registry delivery. Unraid and the operator own image selection, container lifecycle, persistent data, and recovery from existing backups; WUD only reports availability.

## Invariants

- Packaging and publishing do not silently substitute for verification.
- The runtime serves the frontend and API together as a non-root process.
- The unauthenticated service remains reachable only on the household LAN and tailnet. Credentials and journal data never enter the published image or repository.

## Tradeoffs Accepted

- Manual recovery from an existing periodic backup may lose entries recorded after it; a previous image alone may not read a database migrated by a newer version.

## Acceptance Criteria

- Every pull request runs an affected-component Dagger check. Backend, frontend, pipeline, or any combination selected by changed paths receives its applicable checks; backend/contract changes also check contract drift. Review checks never publish.
- A manually dispatched publishing check runs only from merged main, fully verifies the system, assigns one UTC timestamp version, and refuses already-published versions before publication. Failure in verification or registry authentication prevents publication.
- Authenticated publication provides a private `linux/amd64` GHCR image under only its timestamp tag, carrying version and commit provenance and an immutable digest. No `latest` alias or Git release tag is maintained; credentials are not exposed.
- The operator imports the Unraid template once, supplies read-only registry credentials, selects an existing published version, and applies it to run one container with persistent writable journal data. Unraid reports pull and startup failures; the operator verifies that the service reports the selected version and is reachable on the LAN and tailnet, not the public internet.
- WUD is configured once on the NAS to report newer timestamped versions of this service without updating it; no WUD configuration is persisted in the repository. The operator edits the existing Unraid template's version and applies it when ready; journal entries remain available after replacement. Other containers retain their existing update behavior.
- The already-published trial remains runnable and its version checkable until the operator moves to a timestamped image. For incompatible images, the operator stops the service and manually restores a compatible existing Unraid backup before applying a selected version.

## Doc Sync

- `ci/specs/design.md` — Processing rules, Edge cases, and Invariants for affected PR checks, on-demand publication, and operator-owned rollout.
- `ci/specs/contracts.md` — Version, Publish, and Runtime image contracts for timestamp tags and provenance.
- `ci/specs/testing.md` — Traceability and Pipeline validation for PR checks, manual publication, and deployment.
- `ci/specs/operational.md` — Versioning & release provenance, Publishing, and Deployment for both checks, one-time Unraid/WUD setup, manual updates, and recovery.
- `specs/operational.md` — Deployment topology for the running NAS service, volume, network, and health.
- `CONTRIBUTING.md` — Working in the repo: build and release commands.
