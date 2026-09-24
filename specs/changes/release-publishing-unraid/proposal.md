# Versioned GHCR releases and Unraid deployment

> Standard: [Agentic Engineering Standards](https://github.com/Adobe-AIFoundations/agentic-workflow-standards) v1.2.0.
> Lifetime: open from creation through implementation, archived in the [sync docs & archive step](../../agentic-workflows.md#a-structured-development-skills).

**Date:** 2026-09-24

Publish the first versioned plant-journal image and make its NAS rollout and recovery reproducible without an agent.

## What & Why

- The pipeline can build and publish a private image from a guarded tag, but there is no completed release or repeatable first-deploy and recovery path for the household NAS.
- A release has an explicit SemVer identity tied to a specific commit and image. A prerelease proves the real registry-to-NAS path during implementation review; the first stable release is `v1.0.0` from merged main.
- The NAS runs the same image on Unraid and retains the journal across container replacement. WUD shows available stable releases without installing them; deterministic commands prepare a selected release, and the operator applies the saved Unraid configuration to deploy or recover without an agent.

## Invariants

- Full verification precedes publication; packaging and publishing do not silently substitute for verification.
- The runtime serves the frontend and API together as a non-root process.
- The unauthenticated service remains reachable only on the household LAN and tailnet. Credentials and journal data never enter the published image or repository.

## Tradeoffs Accepted

- Updates wait for an operator even when WUD reports a new version; this avoids unattended migration and rollout.
- A previous image alone may not read a database migrated by a newer release. Recovery uses the existing periodic Unraid backups rather than a release-time backup, so restoring may lose care entries recorded since the last backup.

## Acceptance Criteria

- Releases use explicit annotated `vMAJOR.MINOR.PATCH` stable tags or `vMAJOR.MINOR.PATCH-rc.N` prerelease tags, with SemVer major for incompatible changes, minor for backward-compatible features, and patch for fixes. Pushing a selected tag starts a dedicated publishing check that fully verifies the system before publishing the image. The selected tag must identify HEAD unambiguously even when prerelease and stable tags share a commit; dirty, untagged, mistagged, or already published versions fail without replacing an existing image. An implementation-review release candidate can be published and deployed before its PR merges; stable `v1.0.0` is published only from merged main.
- An authenticated publish makes a private GHCR image available for the NAS architecture under write-once version tags and immutable image digests, with commit/version provenance available to operators. `latest` points to the highest published stable SemVer version, including when an older maintenance release is published later; prereleases never change it. Missing or invalid credentials fail visibly and are never printed.
- An operator can import an Unraid template, supply read-only registry credentials, select the prerelease for a real pre-merge trial or a stable version for normal use, and apply the template to start one container with a persistent writable journal volume and a health response reporting the selected version. First-deploy checks confirm LAN and tailnet reachability without public-internet exposure.
- WUD authenticates to the private registry and shows only newer stable SemVer releases for the running service, without automatically pulling, replacing, or restarting it. The prerelease trial does not enter the stable update channel. An available update remains visible until an operator chooses to deploy; unrelated containers keep their existing update behavior.
- Deterministic operator commands prepare the saved Unraid configuration for an explicitly selected version, check prerelease and stable deployments, and restore an available compatible Unraid backup while the service is stopped when recovery needs one. Only the operator's Apply action starts or replaces the container. After a successful replacement, existing journal entries remain available and the healthy service reports the selected version. Missing backups and failed preparation or health checks exit nonzero; Unraid surfaces pull and startup failures. These actions need no coding-agent service or runtime.

## Doc Sync

- `ci/specs/design.md` — Processing rules, Edge cases, and Invariants for releases and manual rollout.
- `ci/specs/contracts.md` — Version, Publish, and Runtime image contracts for prereleases, stable tags, and provenance.
- `ci/specs/testing.md` — Traceability and Pipeline validation for the publishing check and deployment behavior.
- `ci/specs/operational.md` — Versioning & release provenance, Publishing, and Deployment including the publishing check, credentials, pre-merge trial, watch-only WUD, manual rollout, existing Unraid backups, and recovery.
- `specs/operational.md` — Deployment topology for the running NAS service, volume, network, and health.
- `CONTRIBUTING.md` — Working in the repo: release and deploy commands.
