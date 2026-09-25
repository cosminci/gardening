# Structured single-line backend logging

> Standard: [Agentic Engineering Standards](https://github.com/Adobe-AIFoundations/agentic-workflow-standards) v1.2.0.
> Lifetime: open from creation through implementation, archived in the [sync docs & archive step](../../agentic-workflows.md#a-structured-development-skills).

**Date:** 2026-09-25

Give the backend a voice: every unexpected failure and every meaningful state change becomes a single leveled log line, logged exactly once no matter how many layers it passes through.

## What & Why

- Today: the backend produces no log output of its own. A persistence failure becomes a typed result that domain services and HTTP adapters pattern-match on; every adapter that maps such a result to a response discards the underlying cause. Startup and background failures are equally invisible — a fatal startup error surfaces only as an uncaught, multi-line stack trace, and a failed background recomputation is silently swallowed. An operator watching the process has no way to tell an unexpected failure from ordinary quiet operation, or to see which state-changing actions actually happened.
- New: an unexpected failure produces one leveled, single-line log record naming the failing operation and its cause; a successful state-mutating action produces one single-line record naming the action and what it affected; a background failure and the process's own startup and shutdown are equally visible. The same underlying failure never produces more than one log line, however many layers it crosses on its way to a response.

## Domain / Design Notes

- Logging responsibility follows the effect, not the layer count above it: whichever component actually performs a side effect — a database statement, a background recomputation cycle, the composition root's own startup and shutdown — owns logging that side effect's outcome exactly once. A domain service or HTTP adapter that merely translates or forwards an already-classified result is not a logging point and does not log it again.
- Logging is exposed as a capability, the same way the clock and ID generator are: a caller can substitute or suppress it in isolation, the same way tests already substitute those capabilities, rather than it being reachable as a global logger every component can silently depend on.

## Invariants

- Existing HTTP status codes, response bodies, and domain result types are unchanged; logging is an added side channel, not a new decision affecting behavior.
- Watering attention recomputation keeps retrying on its own schedule after a failed cycle; logging that failure does not change its retry behavior.

## Tradeoffs Accepted

- Log lines are unstructured single-line text to stdout/stderr, each standing alone; nothing correlates the several lines produced by one request or one background cycle with a shared identifier. Acceptable at household scale with a single operator reading the raw stream.

## Acceptance Criteria

- An unexpected failure in a database read or write produces exactly one single-line error record naming the failing operation and the underlying cause's type and message; the same failure produces no further log record as it is translated into a domain result or an HTTP response.
- A successful state-mutating action — creating, editing, archiving, or deleting a plant or operation; adding or editing a substrate component or pesticide — produces exactly one single-line info record naming the action and the identifier(s) it affects. A read-only query produces no record on success.
- A background recomputation cycle that fails produces exactly one single-line record for that failure, is not repeated for that same failure elsewhere, and the next cycle still runs on schedule.
- The backend logs one single-line info record with its listening address and version once it is ready to serve requests, and one single-line error record naming the cause before exiting non-zero if it cannot start (an unreachable database or a failed migration).
- Every log record produced by the backend is a single line; none of them prints a raw stack trace.

## Doc Sync

- `CONTRIBUTING.md` — new "Logging" convention naming which component logs (the effect owner, never a translating layer), the info/error level meanings, and the single-line/no-stack-trace rule.
- `specs/operational.md` — Alerts: state that leveled single-line log output is now the backend's operational signal (naming what is and isn't logged), and that there is still no aggregation or alerting on it.
- `.agents/skills/sdd/SKILL.md` — Implementation authoring checklist gains a permanent item enforcing this logging discipline (single effect-owner per failure, never a translating layer; capability, not a global logger; single line; no logging inside pure functions) for every future change.

## Out of Scope

- Structured (JSON) or aggregated logging; today's plain single-line text to stdout/stderr is unchanged in destination, only added in content.
