# Local development from a NAS snapshot

> Standard: [Agentic Engineering Standards](https://github.com/Adobe-AIFoundations/agentic-workflow-standards) v1.2.0.
> Lifetime: open from creation through implementation, archived in the [sync docs & archive step](../../agentic-workflows.md#a-structured-development-skills).

**Date:** 2026-09-24

Give a developer a repeatable local startup workflow with an optional, safe copy of the household journal.

## What & Why

- Today the backend and frontend can be started separately, but the frontend's same-origin API calls do not reach the backend through the development server; reproducing NAS journal state requires undocumented manual database handling.
- A single documented local workflow should start the app with a private local journal, optionally refreshed from a consistent NAS snapshot, without modifying the NAS or accidentally committing household data.

## Invariants

- The running NAS journal remains authoritative and writable by the deployed app throughout local setup.
- NAS credentials and household journal records do not enter the repository or published image.
- Starting the backend continues to apply its normal database migrations; a migration failure prevents startup.

## Tradeoffs Accepted

- A local snapshot is stale as soon as new entries are recorded on the NAS. Local edits are disposable and do not synchronize back.
- Refreshing an existing local journal requires explicit confirmation because it discards local edits.

## Acceptance Criteria

- With documented local toolchain and port prerequisites, a developer can start both the backend and frontend with one documented command, then use the app in a browser with API calls routed to that backend; stopping the workflow stops both processes. The entry point checks its prerequisites before starting either process and reports missing tools, configuration, or unavailable ports without leaving a partially running app.
- By default startup uses an isolated local journal, creates it if absent, and retains local changes across restarts. A fresh developer can start without NAS connectivity.
- On explicit request, the developer can refresh the local journal over SSH from the configured NAS source while the NAS service remains available. Concurrent NAS writes yield a valid point-in-time journal, with plants, catalogs, and operation history from that same point.
- The NAS source and SSH authentication are supplied through local runtime configuration, never stored in the repository or image. A missing source, unavailable SSH access, invalid snapshot, or failed transfer/replacement reports an actionable error without exposing credentials; the previous local journal remains usable. An existing local journal is replaced only after explicit confirmation. An interrupted refresh leaves either the previous complete journal or the new complete journal on the next start, with no partial journal served; failed or abandoned copies containing household data are removed.
- Local development is accessible from the workstation without opening an unauthenticated service to the LAN by default. The local database and transient snapshot are excluded from version control.

## Doc Sync

- `CONTRIBUTING.md` — Working in the repo: document prerequisites, one-command isolated startup, NAS SSH source configuration, explicit confirmation to refresh, and recovery when a refresh fails.
- `specs/operational.md` — Runtime dependencies: document workstation-only exposure, point-in-time NAS snapshot semantics, atomic local replacement across failures or interruption, and that local edits never synchronize back.
