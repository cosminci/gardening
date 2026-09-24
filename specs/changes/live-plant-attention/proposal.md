# Live plant attention

> Standard: [Agentic Engineering Standards](https://github.com/Adobe-AIFoundations/agentic-workflow-standards) v1.2.0.
> Lifetime: open from creation through implementation, archived in the [sync docs & archive step](../../agentic-workflows.md#a-structured-development-skills).

**Date:** 2026-09-25

Push watering attention to the browser live, recompute it roughly ten times more often, and stop letting attention readiness gate the whole journal.

## What & Why

- Attention materializes once, synchronously, before the backend starts serving, and a background job recomputes it every five minutes. The browser fetches it as part of the initial journal load and again after a mutation, falling back to a client-computed watering count for a plant the snapshot doesn't cover; the whole journal fails to load if that count reaches five without a snapshot entry, or if any other part of the load fails: an error banner replaces the garden, and the user has no way to switch views or add a plant while it is shown.
- Startup will no longer wait for or abort on an attention computation; plants, operations, and catalogs become servable as soon as they're ready, independent of attention. Attention will recompute at least every 30 seconds and push live to every connected browser instead of being pulled, replacing the browser's load-time and post-mutation reads with one subscription per session that receives the latest known state immediately, then every later update, and reconnects automatically if the connection drops. An active plant with no entry yet in the current state shows a pending indicator in its leftmost column instead of a status icon, rather than falling back to a client-computed count or failing the journal; the garden/cemetery view and the active/archived toggle render as soon as the non-attention journal data loads, regardless of attention state.

## Domain / Design Notes

```scala
enum AttentionSnapshot:
  case Pending
  case Measured(projection: AttentionProjection)

trait PlantAttentionMonitor:
  def current: AttentionSnapshot
  def refreshAll: RefreshAttentionResult
  def subscribe(): AttentionFeed

trait AttentionFeed:
  def next(): AttentionSnapshot
```

- A monitor starts `Pending` and never regresses once `Measured`; a failed `refreshAll` retains the prior snapshot exactly as today. `subscribe` returns a feed whose first `next()` replays the monitor's current snapshot so a newly connecting browser is never left waiting on the next recomputation; each later `next()` blocks until the following successful `refreshAll` publishes.
- The existing pull-style `GET` read stays; it and the new live feed source from the same monitor state, so a pull reader (including a future metrics adapter) and a subscriber never disagree.
- An active plant absent from the current measured snapshot — because none has ever been computed, or the projection hasn't caught up with a newly active plant — is pending for that plant alone. It no longer falls back to a client-computed watering count, and a pending or not-yet-arrived entry is never treated as a load failure.
- Journal load failure is reserved for plant, operation, substrate, or pesticide read failures, and for a measured snapshot whose plant identifiers are duplicated or fall outside the active set.

## Invariants

- Watering cadence classification (unavailable, current, overdue, red alert) and its 5–20 sample bounds are unchanged.
- Browser ordering of scored plants (unavailable first, then urgency ratio, then the existing tie-break order) is unchanged.
- Recent-card and paginated-history operation-read bounds are unchanged.

## Tradeoffs Accepted

- Startup no longer aborts when the attention store is broken; every active plant instead stays pending indefinitely until the store recovers. This trades a fail-fast startup guarantee for a backend that never blocks on attention.
- Recomputing ten times more often adds background read load against SQLite, and each connected browser holds an open connection for its session instead of one-shot reads; both are acceptable at household scale.

## Acceptance Criteria

- Attention recomputes at least every 30 seconds while the backend runs, and every successful recomputation pushes live to all connected browsers without a poll or manual refresh.
- A browser that opens the live feed immediately receives the latest known state — pending if nothing has been computed yet, otherwise the latest measured projection — then keeps receiving each later update for as long as it stays connected; a dropped connection reconnects automatically and resumes updates without a page reload. While disconnected and before reconnecting, each plant continues showing its last known attention rather than reverting to pending.
- Backend startup no longer waits for or fails on the first attention computation: plants, operations, substrate components, and pesticides remain servable immediately regardless of attention state.
- The garden/cemetery view, the active/archived toggle, and the add-plant control render as soon as plant, operation, substrate, and pesticide data load successfully, independent of attention state.
- An active plant with no entry yet in the current state shows an animated, accessibly labeled pending indicator in its leftmost column in place of a status icon; the indicator is replaced in place by the plant's status icon the moment its entry arrives, and it honors the reduced-motion preference like other card animations.
- The journal load fails only for a plant, operation, substrate, or pesticide read failure, or for a measured attention state with a duplicated plant identifier or a plant outside the active set; a missing or not-yet-arrived attention entry never fails the load.
- The browser shows a small, accessibly labeled connection indicator near the journal header reflecting whether its live attention feed is currently connected to the backend — one state while connected, a distinct state while disconnected or reconnecting — independent of and in addition to each plant's pending indicator.

## Doc Sync

- `GLOSSARY.md` — define "Pending attention": the state of an active plant with no entry in the current attention state, shown as a pending indicator rather than a status icon.
- `specs/design.md` — Overview and Domain model: attention is asynchronously computed and pushed rather than periodically pulled, and startup no longer blocks on or aborts for it. Use cases and workflows: replace "an initial attention read must succeed to serve the app" with the async/pending/live-feed behavior, the browser's live-feed connection indicator, and garden rendering that no longer depends on attention.
- `specs/operational.md` — Scaling characteristics: state the 30-second recomputation cadence and that each connected browser holds one open live-feed connection.
- `specs/testing.md` — Strategy: extend the existing browser-reconciliation bullet to cover independently-arriving pending and measured attention snapshots via the live feed, decoupled from journal load success or failure.

## Out of Scope

- A metrics-vendor adapter consuming the live feed.
- Any change to watering cadence classification math or sample bounds.
