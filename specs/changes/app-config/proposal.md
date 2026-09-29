# Externalize business and deployment configuration

> Standard: [Agentic Engineering Standards](https://github.com/Adobe-AIFoundations/agentic-workflow-standards) v1.2.0.
> Lifetime: open from creation through implementation, archived per [the SDD skill's Archive PR phase](../../../.agents/skills/sdd/SKILL.md).

**Date:** 2026-09-29

**Classification:** feature

**Grounded in:** Spiked two ways to load configuration: hand-written parsing on top of Typesafe Config, versus PureConfig's declarative case-class mapping. PureConfig's squants and Iron integration modules covered every value shape in use today, including the refined watering-count bound, with no hand-written glue.

One validated configuration surface replaces hardcoded thresholds and scattered, ad hoc environment parsing.

## What & Why

Today:

- Watering min/max sample count, the overdue grace period, the attention recompute interval, and photo upload/thumbnail size caps are hardcoded. Changing any needs a code change and a rebuild.
- The watering history behind that average is capped at 20 records, with no way to widen or narrow it.
- Network host/port and deployment paths are each read from their own environment variable, parsed ad hoc, with an inline fallback.
- `/health` reports a build version alongside its liveness status, coupling an operational liveness check to release tracking.

New:

- Every threshold above gets a named environment variable with a fallback default matching today's value.
- The 20-record cap is removed. An operator can configure any window of 2 or more records.
- Every environment-sourced setting is validated once, together, at startup, not parsed case by case as each is first needed.
- Every environment variable name drops its current app-name prefix; the app has nothing else to disambiguate from.
- `/health` reports liveness only. The application-version concept is removed outright, not folded into the configuration surface.

## Domain / Design Notes

Configuration fields fall into two tiers.

**Fixed** — not sourced from the environment in this single-container deployment:

- Storage lock-contention timeout and attention-feed connection-staleness threshold: unchanged from today.

**Overridable** — sourced from the named environment variable when present and non-empty, otherwise the documented default:

| Field | Environment variable | Default | Bound |
| --- | --- | --- | --- |
| Network bind host | `HOST` | `0.0.0.0` | non-empty (unchanged) |
| Network bind port | `PORT` | `8080` | non-empty (unchanged) |
| Watering minimum sample count | `WATERING_MIN_SAMPLE_COUNT` | 5 | 2 or more |
| Watering maximum sample count | `WATERING_MAX_SAMPLE_COUNT` | 20 | ≥ the configured minimum, no upper limit |
| Watering overdue grace period | `WATERING_OVERDUE_GRACE_PERIOD` | 24h | greater than zero |
| Attention recompute interval | `ATTENTION_RECOMPUTE_INTERVAL` | 30s | greater than zero |
| Photo maximum upload size | `PHOTO_MAX_UPLOAD_SIZE` | 20MiB | greater than zero |
| Photo maximum thumbnail size | `PHOTO_MAX_THUMBNAIL_SIZE` | 100KiB | greater than zero |
| Database file path | `DB_PATH` | `gardening.db` | non-empty (unchanged) |
| Photos directory | `PHOTOS_DIR` | `photos` | non-empty (unchanged) |
| Static assets directory | `STATIC_DIR` | `static` | non-empty (unchanged) |

- Minimum's floor is 2: averaging needs at least two dates to produce one interval.
- Below the minimum, watering is reported unavailable, not computed from too little data.
- The window is otherwise unbounded — it holds whatever the configured maximum requests.

## Alternatives Considered

- Typesafe Config, used directly with hand-written per-field parsing: rejected. Every field would need its own hand-written conversion and validation code.

## Acceptance Criteria

- Every overridable field takes its default when its environment variable is unset or empty — matches today's behavior exactly.
- An override outside its bound fails startup, naming the field and the rejected value. A minimum sample count below 2 is one such rejected value.
- Minimum sample count above the configured maximum (or vice versa) fails startup, regardless of either value's own bound.
- A configured maximum sample count above 20 is honored exactly, with no upper limit.
- Every renamed environment variable (host, port, database path, photos directory, static assets directory) keeps its current default and override behavior under its new name.
- Setting none of the new environment variables changes nothing observable — this change alone is a no-op until an operator opts in.
- `/health`'s response body reports only a liveness status; it carries no version field.
- No environment variable, config field, or response body reports an application version anywhere.

## Doc Sync

- `specs/operational.md` — Runtime dependencies: name every environment-variable configuration input (renamed deployment paths/host/port, plus the new watering/attention/photo thresholds) with its current default and bound.
- `unraid/README.md` — post-update verification: replace the `/health` version check in the verification and recovery steps, since the response no longer carries a version.

## Out of Scope

- Frontend-side limits that duplicate these backend values (upload size, accepted image types, photo page size) — maintained independently, not addressed here.
- No runtime reconfiguration path; changing any value still requires restarting the service.
