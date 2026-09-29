# Externalize business and deployment configuration

> Standard: [Agentic Engineering Standards](https://github.com/Adobe-AIFoundations/agentic-workflow-standards) v1.2.0.
> Lifetime: open from creation through implementation, archived per [the SDD skill's Archive PR phase](../../../.agents/skills/sdd/SKILL.md).

**Date:** 2026-09-29

**Grounded in:** Spiked configuration loading against the value shapes actually in use today — a bounded integer count reusing the watering sample count's existing 1–20 domain bound, a byte size, and a duration. Confirmed each can be sourced from an environment variable with a documented fallback default and validated against its existing bound at load time, surfacing an out-of-bound override as a startup failure naming the offending value, without hand-written per-value conversion code.

Replace scattered hardcoded thresholds and ad hoc, individually-parsed environment lookups with one validated configuration surface, loaded once at startup: values with real deployment-tuning value become overridable by a documented environment variable with a fallback default, and values that must never vary in this single-container deployment become fixed.

## What & Why

- Today: the minimum and maximum number of past waterings considered when assessing a plant's attention, the grace period added before an overdue plant escalates to the most urgent alert level, the interval between attention recomputation passes, and the maximum accepted photo upload and thumbnail sizes are all hardcoded literals with no way to change them short of a code change and rebuild. Separately, the network bind host and port, and a handful of deployment paths and the running version, are each read from an individually-named environment variable with its own inline fallback, parsed ad hoc wherever it's needed.
- New: every business threshold above is overridable by a documented, individually-named environment variable, each with a fallback default equal to today's hardcoded value; an out-of-range override prevents startup with a message naming the offending value. The network bind host and port become fixed values with no environment override. Every environment-sourced setting — the existing deployment paths and version, and the new thresholds — is validated together, once, at startup, rather than parsed case by case as each is first needed.

## Domain / Design Notes

Configuration fields fall into two tiers.

**Fixed** — a literal value, not sourced from the environment in this single-container deployment:

- Network bind host and port — `0.0.0.0`, `8080`, matching today's values (see Acceptance Criteria for the environment-variable behavior change).
- The storage layer's internal lock-contention timeout and the attention feed's connection-staleness threshold (used to expire an idle WebSocket connection) — unchanged from their current values.

**Overridable** — sourced from the named environment variable when present and non-empty, otherwise the documented default:

| Field | Environment variable | Default | Bound |
| --- | --- | --- | --- |
| Watering minimum sample count | `GARDENING_WATERING_MIN_SAMPLE_COUNT` | 5 | 1–20, and ≤ the maximum sample count |
| Watering maximum sample count | `GARDENING_WATERING_MAX_SAMPLE_COUNT` | 20 | 1–20 |
| Watering overdue grace period | `GARDENING_WATERING_OVERDUE_GRACE_PERIOD` | 24h | greater than zero |
| Attention recompute interval | `GARDENING_ATTENTION_RECOMPUTE_INTERVAL` | 30s | greater than zero |
| Photo maximum upload size | `GARDENING_PHOTO_MAX_UPLOAD_SIZE` | 20MB | greater than zero |
| Photo maximum thumbnail size | `GARDENING_PHOTO_MAX_THUMBNAIL_SIZE` | 100KB | greater than zero |
| Application version | `GARDENING_APP_VERSION` | `0.0.0-dev` | non-empty (unchanged) |
| Database file path | `GARDENING_DB_PATH` | `gardening.db` | non-empty (unchanged) |
| Photos directory | `GARDENING_PHOTOS_DIR` | `photos` | non-empty (unchanged) |
| Static assets directory | `GARDENING_STATIC_DIR` | `static` | non-empty (unchanged) |

The watering sample count bound (1–20) is the same domain bound the rolling watering-sample window already enforces (see Invariants) — an override is rejected using that existing bound, not a separately declared one.

## Alternatives Considered

- Making the photo list's page size configurable too: rejected — the frontend always sends an explicit page size and never relies on the server-side default, so making it operator-tunable would tune a value nothing observes. The unused default is instead raised to the page-size ceiling rather than left at an arbitrary smaller number.
- Making the operation history's page size configurable: rejected — the number of operations shown together is fixed by how many fit legibly in the current layout, not a deployment concern; varying it independently of that layout would break the display it was tuned for.
- Allowing an environment override for the network bind host and port: rejected — this service always runs as one container on one deployment target with one fixed port mapping; nothing in the deployment ever needs a second value.

## Invariants

- The rolling watering-sample window never holds more than 20 samples, regardless of the configured maximum sample count.

## Tradeoffs Accepted

- Any deployment currently setting `GARDENING_HOST` or `GARDENING_PORT` loses that override silently — the variable is simply ignored rather than rejected at startup. Borne by whoever operates such a deployment; today's production deployment sets neither.

## Acceptance Criteria

- Every overridable field takes its documented default when its environment variable is unset or empty, exactly matching today's hardcoded behavior.
- Setting an overridable field's environment variable to a value outside its bound prevents the service from starting, and the failure names the field and the rejected value.
- Setting the watering minimum sample count above the configured maximum (or the maximum below the configured minimum) prevents the service from starting, independent of either value's own individual bound.
- `GARDENING_HOST` and `GARDENING_PORT` are no longer read; setting either has no effect, and the service always binds to its fixed host and port.
- The application version, database file path, photos directory, and static assets directory environment variables keep their current names, defaults, and override behavior unchanged.
- A deployment that sets none of the new environment variables sees no change in watering attention, attention recomputation cadence, or accepted photo/thumbnail sizes — this change alone alters no runtime behavior until an operator opts in.

## Doc Sync

- `specs/operational.md` — Runtime dependencies: name every environment-variable configuration input (the existing deployment paths and version, plus the new watering, attention, and photo thresholds) with its default and bound, and state that `GARDENING_HOST`/`GARDENING_PORT` no longer have any effect.

## Out of Scope

- Frontend-side limits that duplicate these backend values (upload size, accepted image types, photo page size) are not addressed here; they continue to be maintained independently.
- Changing any of these values still requires restarting the service; no runtime reconfiguration path is introduced.
