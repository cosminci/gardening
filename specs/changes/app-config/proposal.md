# Externalize business and deployment configuration

> Standard: [Agentic Engineering Standards](https://github.com/Adobe-AIFoundations/agentic-workflow-standards) v1.2.0.
> Lifetime: open from creation through implementation, archived per [the SDD skill's Archive PR phase](../../../.agents/skills/sdd/SKILL.md).

**Date:** 2026-09-29

**Grounded in:** Spiked configuration loading for the value shapes in use today: a bounded integer count, a byte size, a duration. Confirmed each can be sourced from an environment variable with a fallback default, validated at load time. The bounded count reuses the watering sample count's existing 1–20 ceiling instead of declaring a new one. An out-of-bound override fails startup, naming the offending value.

One validated configuration surface replaces hardcoded thresholds and scattered, ad hoc environment parsing. Operator-tunable values become overridable with a documented default. Values that must never vary in this single-container deployment stay fixed.

## What & Why

Today:

- Watering min/max sample count, the overdue grace period, the attention recompute interval, and photo upload/thumbnail size caps are hardcoded. Changing any needs a code change and a rebuild.
- Network host/port, deployment paths, and the running version are each read from their own environment variable, parsed ad hoc, with an inline fallback.

New:

- Every threshold above gets a named environment variable with a fallback default matching today's value.
- An out-of-range override fails startup, naming the offending value.
- Host and port become fixed; no environment override.
- Every environment-sourced setting is validated once, together, at startup — not parsed case by case as each is first needed.

## Domain / Design Notes

Configuration fields fall into two tiers.

**Fixed** — not sourced from the environment in this single-container deployment:

- Network bind host and port: `0.0.0.0`, `8080`. Unchanged from today.
- Storage lock-contention timeout and attention-feed connection-staleness threshold: unchanged from today.

**Overridable** — sourced from the named environment variable when present and non-empty, otherwise the documented default:

| Field | Environment variable | Default | Bound |
| --- | --- | --- | --- |
| Watering minimum sample count | `GARDENING_WATERING_MIN_SAMPLE_COUNT` | 5 | 1–20 |
| Watering maximum sample count | `GARDENING_WATERING_MAX_SAMPLE_COUNT` | 20 | 1–20 |
| Watering overdue grace period | `GARDENING_WATERING_OVERDUE_GRACE_PERIOD` | 24h | greater than zero |
| Attention recompute interval | `GARDENING_ATTENTION_RECOMPUTE_INTERVAL` | 30s | greater than zero |
| Photo maximum upload size | `GARDENING_PHOTO_MAX_UPLOAD_SIZE` | 20MiB | greater than zero |
| Photo maximum thumbnail size | `GARDENING_PHOTO_MAX_THUMBNAIL_SIZE` | 100KiB | greater than zero |
| Application version | `GARDENING_APP_VERSION` | `0.0.0-dev` | non-empty (unchanged) |
| Database file path | `GARDENING_DB_PATH` | `gardening.db` | non-empty (unchanged) |
| Photos directory | `GARDENING_PHOTOS_DIR` | `photos` | non-empty (unchanged) |
| Static assets directory | `GARDENING_STATIC_DIR` | `static` | non-empty (unchanged) |

The 1–20 bound reuses the existing watering-sample-window ceiling (see Invariants), not a new one.

## Alternatives Considered

- Photo list page size configurable: rejected. The frontend always sends an explicit page size; the server default is never read. The unused default is raised to the page-size ceiling instead of left arbitrary.
- Operation history page size configurable: rejected. The count shown together is fixed by the current layout, not a deployment concern.
- Environment override for host/port: rejected. One container, one deployment target, one port mapping.

## Invariants

- The rolling watering-sample window caps at 20 samples, regardless of the configured maximum.

## Tradeoffs Accepted

- An operator still setting `GARDENING_HOST`/`GARDENING_PORT` gets no warning it's ignored — no startup failure, just a silently wrong assumption about which host/port is bound. Borne by whoever operates such a deployment; today's production deployment sets neither.

## Acceptance Criteria

- Every overridable field takes its default when its environment variable is unset or empty — matches today's behavior exactly.
- An override outside its bound fails startup, naming the field and the rejected value.
- Minimum sample count above the configured maximum (or vice versa) fails startup, regardless of either value's own bound.
- `GARDENING_HOST`/`GARDENING_PORT` are no longer read. Setting either has no effect; the service always binds to its fixed host and port.
- Version, database path, photos directory, and static assets directory keep their current names, defaults, and override behavior.
- Setting none of the new environment variables changes nothing observable — this change alone is a no-op until an operator opts in.

## Doc Sync

- `specs/operational.md` — Runtime dependencies: name every environment-variable configuration input (existing deployment paths/version, plus the new watering/attention/photo thresholds) with its default and bound; state that `GARDENING_HOST`/`GARDENING_PORT` no longer have any effect.

## Out of Scope

- Frontend-side limits that duplicate these backend values (upload size, accepted image types, photo page size) — maintained independently, not addressed here.
- No runtime reconfiguration path; changing any value still requires restarting the service.
