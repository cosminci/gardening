# Plant attention ordering and watering warnings

> Standard: [Agentic Engineering Standards](https://github.com/Adobe-AIFoundations/agentic-workflow-standards) v1.2.0.
> Lifetime: open from creation through implementation, archived in the [sync docs & archive step](../../agentic-workflows.md#a-structured-development-skills).

**Date:** 2026-09-22

Prioritize active plants from bounded watering history and identify overdue watering without moving care rules into the browser.

## What & Why

- Active plants are currently ordered by display name in the browser; the backend exposes no watering cadence, urgency, or warning.
- The backend will publish an ordered plant-attention projection derived from care operations containing `watered`; the browser will render that projection without calculating or sorting attention.
- The same vendor-neutral projection will support HTTP now and a future metrics adapter without coupling attention rules to either consumer.

## Domain / Design Notes

- A projection has one measurement time and an ordered active-plant collection. Each plant includes its qualifying sample count, elapsed time when at least one sample exists, and either unavailable cadence or inferred average interval, urgency, and current/overdue warning state.
- Cadence inference uses the latest 5–20 qualifying operations, ordered by timestamp descending and operation identifier descending. It computes the arithmetic mean of consecutive watering intervals; fewer than five samples is unavailable, and more than 20 uses only the latest 20.
- Urgency is elapsed time from the latest watering to the injected clock divided by the inferred average interval. Overdue begins when elapsed time is at least the average interval plus 24 hours.
- If equal timestamps produce a zero average interval, urgency is zero at that timestamp and unbounded once elapsed time is positive; unbounded urgency sorts above finite urgency.
- Unknown cadence sorts before every scored plant. Scored plants sort by urgency descending. Equal states or urgency use display name ascending, then plant identifier ascending.
- Pure cadence inference, urgency and warning evaluation, and ordering policies remain separate from materialization, operation retrieval, and transport translation.
- The existing bounded operation-read capability expands to a maximum request size of 20 and supports use-case selection before the limit is applied. Recent cards continue to request three unfiltered operations, history requests up to ten unfiltered operations per page, and cadence inference requests the latest 20 operations matching care with `watered`.
- The backend attempts initial materialization at startup, refreshes the affected plant after a successful watering log or an edit that adds or removes `watered`, and refreshes time-derived values every five minutes. Readers observe only complete snapshots.
- A vendor-neutral read port exposes the latest projection and its measurement time. HTTP translates that contract into wire models outside the core; a future metrics adapter can consume the same port regardless of whether export later uses scrape or push.
- A failed initial refresh leaves the projection unavailable. A later failed refresh reports failure and retains the previous complete snapshot with its original measurement time.

## Invariants

- Recent-operation and paginated-history reads remain bounded and retain their existing ordering, defaults, and failure behavior.
- Logging and editing retain their existing persistence, compensation, serialization, and focus-restoration behavior.
- Core attention contracts contain domain values only; HTTP, persistence, scheduling, and metrics technologies remain adapter concerns.

## Tradeoffs Accepted

- Time-derived attention may lag wall-clock changes by up to five minutes between successful refreshes. This bounds refresh work independently of browser or future metrics traffic while adding memory and background work proportional to active plants and at most 20 watering timestamps per plant.

## Acceptance Criteria

- The projection uses only `watered` care operations: fewer than five yields unavailable cadence; exactly five yields a score; more than 20 uses the latest 20; repots and non-watering care do not affect the result.
- Every item exposes measurement time and sample count; elapsed time is present after the first watering. Scored items additionally expose arithmetic-mean interval, urgency, and warning state. Immediately below average plus 24 hours is current; exactly at and above it is overdue. Unknown cadence has no average or urgency and never reports overdue.
- Unknown plants precede all scored plants; scored plants are ordered by urgency descending, placing recently watered plants near the bottom. Equal timestamps, zero average intervals, unknown states, and equal urgency follow the documented urgency and tie-break rules.
- The initial materialization, each five-minute refresh, a logged watering, and edits that add or remove `watered` publish a newly measured and ordered complete projection. Time alone can reorder plants or change warning state without a new operation.
- The shared operation-read capability applies selection and ordering before its requested limit. Cadence reads at most 20 qualifying waterings per active plant, while existing recent-operation and paginated-history reads retain their current sizes, ordering, defaults, and behavior.
- The browser fetches the projection on load and after a successful operation save, with no polling or server-pushed updates, and renders the backend order and cadence state. Overdue cards show a large `!` with an accessible watering-overdue name and warning semantics; unavailable cadence is labelled distinctly and has no overdue warning.
- Warning presentation remains usable at desktop and landscape-mobile widths, does not rely on color, and preserves card controls, keyboard behavior, and focus when a refreshed projection reorders cards.
- HTTP and a future metrics adapter can consume the same vendor-neutral projection port. The feature introduces no metrics vendor dependency, export protocol, configuration, export schedule, or metrics adapter.
- Projection refresh failure never publishes a partial snapshot: reads report unavailable before the first successful refresh and retain the prior measurement after a later failed refresh while the refresh result reports failure.

## Doc Sync

- `GLOSSARY.md` — define plant attention, watering cadence, urgency, and overdue.
- `specs/design.md` — Domain model, Processing rules, Edge cases, and Component architecture for attention calculation, materialization, bounded retrieval, and the future metrics seam.
- `specs/contracts.md` — HTTP API and error behavior for the attention projection.
- `specs/testing.md` — Service-specific strategy, fixtures, and integration boundaries for cadence, materialization, ordering, transport, and warning presentation.
- `specs/operational.md` — Scaling characteristics of five-minute materialization and bounded per-plant sampling.

## Out of Scope

- VictoriaMetrics export, configuration, scheduling, scrape/push choice, and adapters.
- Predictive cadence models beyond the arithmetic mean.
