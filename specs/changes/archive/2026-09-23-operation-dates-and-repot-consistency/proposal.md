# Journal dates and plant data freshness

> Standard: [Agentic Engineering Standards](https://github.com/Adobe-AIFoundations/agentic-workflow-standards) v1.2.0.
> Lifetime: archived after implementation and living-doc sync.

**Date:** 2026-09-23

Give the person logging an operation control over when it happened, make dates readable, and read current plant details independently of periodic watering attention.

## What & Why

- Before first deployment, three development-era schema versions can become one baseline. The existing baseline alone still enforces unique operation timestamps, despite the later versions allowing ties and making timestamp ordering consistent.
- Today the backend assigns the logging time, so a past operation cannot be recorded at its actual time. New operations instead use a date and time chosen in the browser, initially the current local minute.
- Recent and historical operations currently show the same numeric date. Recent entries should read, for example, `23rd of September`; historical rows should read `23.09.2026`.
- A successful latest repot persists the plant's new substrate, but the browser reloads plant details from a periodically refreshed attention snapshot. This can show the old substrate alongside the new repot until the next refresh. The problem is stale presentation, not a failed repot write or an older repot taking precedence. Current plant details also need to be available independently, including for archived plants.

## Domain / Design Notes

- Logging takes an absolute operation instant supplied by the caller alongside the operation details; the server validates and persists that instant rather than choosing it. The browser converts the selected local date and time to an absolute instant. Existing operations retain immutable timestamps when edited.
- The plant read returns current details selected by status, defaulting to active and supporting archived on request. Watering attention reports only plant identity, measurement time, and watering state for active plants; consumers associate each measurement with current plant details by identity. The measured watering state keeps its existing refresh cadence.

## Alternatives Considered

- Refresh the combined attention-and-plant snapshot after every repot: rejected because it still duplicates plant details in the attention contract and couples current plant state to periodic watering measurements.

## Invariants

- Operation pages are ordered newest first by timestamp, then by identifier when timestamps match.
- Editing an operation cannot change its plant, kind, identifier, or timestamp.
- A successful latest repot determines the plant's persisted current substrate; an older repot and ordinary care do not replace it.
- Invalid stored plant data is reported rather than hidden by active/archived filtering.

## Tradeoffs Accepted

- Consolidating the pre-deployment baseline does not preserve local development databases that already applied the superseded versions; recreating those databases is acceptable before deployment.
- Manually entered times can be earlier than other operations, so a new repot is not necessarily the latest repot. The existing latest-repot rule remains authoritative.
- The browser makes a separate plant read to display fresh details while keeping watering measurements on their periodic cadence.

## Acceptance Criteria

- A fresh database reaches the complete current journal schema from one migration version, permits operations for the same plant at the same time, and keeps date-plus-identifier ordering and canonical timestamp persistence.
- The recent three operations show local dates with correct English ordinal suffixes, including 11th, 12th, and 13th; historical pages show local dates as `dd.mm.yyyy`. Both views retain a machine-readable operation instant and an intelligible edit control.
- The log-operation sheet offers a labeled, keyboard-editable local date-and-time control initialized to the current minute. Saving either its default or an edited value records that exact selected minute as the operation instant, preserving the intended local time on readback; invalid or missing dates remain in the form with an accessible error rather than recording an operation.
- The server requires a valid absolute date for new operations and reports invalid or missing dates as input errors. Editing an existing operation still preserves its original timestamp.
- Plant reads return current active plants by default and archived plants when requested; unknown status values are input errors. The journal still displays active plants, while archived plants remain retrievable independently of watering attention.
- Watering attention includes each active plant's identity and measurement, but not its details; a plant with insufficient watering history still has an unavailable measurement. Current plant details and their matching attention are joined by identity for display.
- After successfully logging a latest repot or amending one, the plant displays its new current substrate immediately, alongside its updated operations, without waiting for a watering-attention refresh. A historical repot and a care operation preserve the current substrate. A failed plant read or unmatched measurement is shown as a load failure rather than stale successful state.

## Doc Sync

At archive, review the living docs against the archived care-journal, nomenclature, and history changes, the completed attention change, and the implemented behavior. Retain only current, significant facts in each template's owning section; remove stale claims and duplicated detail.

- `GLOSSARY.md` — Plant status and plant attention definitions once archived plants can be read and attention no longer carries plant details.
- `specs/design.md` — Rework Domain model and its diagram around current plant, operation, catalog, and attention relationships; reconcile Processing rules, Edge cases, Invariants, and Component architecture with timestamp ownership, local presentation, status-filtered reads, and identity-matched attention.
- `specs/contracts.md` — HTTP API and Error responses for submitted dates, status-filtered plant reads, and identity-only attention; reconcile the endpoint summary with the generated contract; update Versioning & compatibility for the pre-deployment baseline.
- `specs/testing.md` — Service-specific strategy and Fixtures & data setup for submitted dates, date display, the consolidated schema, active/archived reads, and repot freshness; remove superseded upgrade-test claims.
- `specs/operational.md` — Runtime dependencies for the consolidated baseline and local development database recreation; verify Scaling characteristics against current behavior.
