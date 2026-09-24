# Live plant attention

> Standard: [Agentic Engineering Standards](https://github.com/Adobe-AIFoundations/agentic-workflow-standards) v1.2.0.
> Lifetime: open from creation through implementation, archived in the [sync docs & archive step](../../agentic-workflows.md#a-structured-development-skills).

**Date:** 2026-09-25

Push attention to the browser over a WebSocket instead of polling, and stop letting attention block the journal.

## What & Why

- Today: attention materializes once at startup (startup aborts if that fails) and recomputes every five minutes. The browser reads it via `GET /attention` on load and after every mutation. A plant missing from the snapshot falls back to a client-computed watering count; the journal — including the active/archived toggle — fails to load if that count reaches five, or if any other read fails.
- New: attention recomputes every 30 seconds and is pushed to every connected browser over a WebSocket; the browser stops requesting it. Startup no longer waits on or aborts for the first computation. A plant the browser hasn't received attention for yet shows a pending indicator instead of failing the journal or falling back to a client-computed count; the garden/cemetery view and the toggle render independently of attention.

## Domain / Design Notes

- `PlantAttentionMonitor` is unchanged: one `current: AttentionProjection`, refreshed every 30 seconds instead of five minutes. Attention computation and classification stay exactly as they are.
- The frontend now holds an open WebSocket connection to the backend. Each time the monitor's current projection changes, the backend pushes it down every open connection; this is a push adapter around the existing monitor, not a new domain port or type.
- Pending is a browser-side fact, not a monitor state: a plant absent from the projection the browser has received is pending for that plant. The monitor never represents "pending."

## Invariants

- Watering cadence classification (unavailable, current, overdue, red alert) and its 5–20 sample bounds are unchanged.
- Browser ordering of scored plants (unavailable first, then urgency ratio, then the existing tie-break order) is unchanged.
- Recent-card and paginated-history operation-read bounds are unchanged.

## Tradeoffs Accepted

- Startup no longer aborts when the attention store is broken; every active plant instead stays pending until the store recovers.
- Recomputing ten times more often adds background read load, and each connected browser holds an open connection instead of one-shot reads; both are acceptable at household scale.

## Acceptance Criteria

- Attention recomputes every 30 seconds; each recomputation reaches every connected browser through the WebSocket push, with no polling.
- A browser that connects or reconnects receives the last computed projection immediately if one exists, then every later update.
- Backend startup succeeds and serves plants, operations, substrates, and pesticides even before attention has ever been computed, or while its store is unavailable.
- The garden/cemetery view, the active/archived toggle, and the add-plant control render as soon as plant, operation, substrate, and pesticide data load, regardless of attention state; the journal fails to load only for a plant, operation, substrate, or pesticide read failure, or a projection with a duplicated plant identifier or a plant outside the active set — never for a missing or pending attention entry.
- Each active plant not yet covered by the browser's received attention shows an animated, accessibly labeled pending indicator in its leftmost column instead of a status icon, replaced in place the moment its entry arrives, honoring reduced motion; during a dropped connection a plant keeps its last known attention instead of reverting to pending.
- The browser shows an accessibly labeled connection indicator near the journal header reflecting whether its WebSocket connection to the backend is currently up.

## Doc Sync

- `specs/design.md` — Use cases and workflows: change the `Monitor -->|complete snapshot| Browser` diagram edge to a WebSocket push, and drop "an initial attention read must succeed to serve the app," which is no longer true.
- `specs/contracts.md` — Contract inventory: add a row for the WebSocket attention feed between browser and backend, a second protocol surface alongside the HTTP API and not covered by the generated OpenAPI.

## Out of Scope

- An in-process metrics adapter reading the monitor directly.
- Any change to watering cadence classification math or sample bounds.
