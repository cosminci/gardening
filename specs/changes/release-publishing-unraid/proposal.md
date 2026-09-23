# Versioned GHCR releases and Unraid deployment

> Standard: [Agentic Engineering Standards](https://github.com/Adobe-AIFoundations/agentic-workflow-standards) v1.2.0.
> Lifetime: open from creation through implementation, archived in the [sync docs & archive step](../../agentic-workflows.md#a-structured-development-skills).

**Date:** 2026-09-24

Publish the first versioned plant-journal image and make its NAS rollout and recovery reproducible without an agent.

## What & Why

- The pipeline can build and publish a private image from a guarded tag, but there is no completed release or repeatable first-deploy and recovery path for the household NAS.
- A release has an explicit SemVer identity tied to a specific commit and image. A prerelease proves the real registry-to-NAS path during implementation review; the first stable release is `v1.0.0` from merged main.
- The NAS runs the same image on Unraid, retains the journal across container replacement, and receives later stable releases through WUD. Operators can deploy and recover using documented, deterministic commands without an agent.

## Invariants

- Full verification precedes publication; packaging and publishing do not silently substitute for verification.
- The runtime serves the frontend and API together as a non-root process.
- The unauthenticated service remains reachable only on the household LAN and tailnet. Credentials and journal data never enter the published image or repository.

## Tradeoffs Accepted

- Stable updates may arrive without an operator initiating a rollout. Automatic updates exclude prereleases; recovery requires an explicit operator action.
- A previous image alone may not read a database migrated by a newer release. Recovery may require restoring the matching pre-update data backup, so each automatic rollout must first save one.

## Acceptance Criteria

- Releases use explicit annotated `vMAJOR.MINOR.PATCH` stable tags or `vMAJOR.MINOR.PATCH-rc.N` prerelease tags, with SemVer major for incompatible changes, minor for backward-compatible features, and patch for fixes. Full verification precedes each publish. The selected tag must identify HEAD unambiguously even when prerelease and stable tags share a commit; dirty, untagged, mistagged, or already published versions fail without replacing an existing image. An implementation-review `v1.0.0-rc.1` can be published and deployed before its PR merges; stable `v1.0.0` is published only from merged main.
- An authenticated publish makes a private GHCR image available for the NAS architecture under write-once version tags and immutable image digests, with commit/version provenance available to operators. `latest` points to the highest published stable SemVer version, including when an older maintenance release is published later; prereleases never change it. Missing or invalid credentials fail visibly and are never printed.
- An operator can import an Unraid template, supply read-only registry credentials, select the prerelease for a real pre-merge trial or a stable version for normal use, and start one container with a persistent writable journal volume and a health response reporting the selected version. First-deploy checks confirm LAN and tailnet reachability without public-internet exposure.
- For normal stable deployments, WUD has an enabled update path authenticated to the private registry that selects only newer stable SemVer versions; the prerelease trial cannot silently enter that path. Before any automatic replacement, a consistent journal backup associated with the current image version is saved, and a failed backup blocks replacement. A successful replacement preserves the journal and reports the new version healthy; failed pulls, starts, or health checks are observable failures rather than reported successes.
- Deterministic operator commands cover first deploy, prerelease smoke testing, backup inspection, and recovery to an explicitly selected previous version with its compatible saved data. Recovery prevents WUD from immediately reinstalling the failed version; missing backups and other command failures exit nonzero. These actions need no coding-agent service or runtime.

## Doc Sync

- `ci/specs/design.md` — Processing rules, Edge cases, and Invariants for release and automatic rollout.
- `ci/specs/contracts.md` — Version, Publish, and Runtime image contracts for prereleases, stable tags, and provenance.
- `ci/specs/testing.md` — Traceability and Pipeline validation for publish and deployment behavior.
- `ci/specs/operational.md` — Versioning & release provenance, Publishing, and Deployment including credentials, pre-merge trial, WUD, backup, and recovery.
- `specs/operational.md` — Deployment topology for the running NAS service, volume, network, and health.
- `CONTRIBUTING.md` — Working in the repo: release and deploy commands.
