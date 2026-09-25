# Backend logging

> Standard: [Agentic Engineering Standards](https://github.com/Adobe-AIFoundations/agentic-workflow-standards) v1.2.0.
> Lifetime: open from creation through implementation, archived in the [sync docs & archive step](../../agentic-workflows.md#a-structured-development-skills).

**Date:** 2026-09-25

Add leveled, single-line logging to the backend. There is none today.

## What & Why

- Today: no log output. A persistence failure is a typed result (e.g. `AddFailed(cause)`); every adapter that maps it to an HTTP response discards `cause`. A fatal startup error is an uncaught exception with a multi-line stack trace. A failed background recomputation is silently dropped. Nothing distinguishes "working fine" from "just swallowed an error."
- New: whichever component performs a side effect logs its own outcome exactly once — error for an unexpected failure (operation + cause), info for a successful mutation (action + id). One failure produces exactly one log line, regardless of how many layers pass it upward.

## Domain / Design Notes

Logging is a capability, threaded the same way `Clock` and `IdGenerator` already are — built once in the composition root, passed via `using`, substitutable in tests. Not a global/static logger.

```scala
// before
def make(using store: PlantJournalStore^, idGen: IdGenerator^): PlantJournal^{store, idGen}

// after
def make(using store: PlantJournalStore^, idGen: IdGenerator^, log: Logger^): PlantJournal^{store, idGen, log}
```

Only the component that performs the effect logs it:

```scala
store.addPlant(plant) match
  case AddPlantResult.Added             => log.info(s"plant created id=${plant.id.value}"); CreatePlantResult.Created(plant)
  case AddPlantResult.AddFailed(cause)  => log.error(s"add plant failed: ${cause.getClass.getSimpleName}: ${cause.getMessage}")
                                            CreatePlantResult.CreateFailed(cause)
```

The HTTP adapter that turns `CreateFailed` into a 500 does **not** log it again — the cause was already logged where it happened.

## Invariants

- HTTP status codes, response bodies, and domain result types are unchanged; logging is a side channel, not a new decision.
- Background recomputation keeps retrying on schedule after a failed cycle; logging a failure doesn't change that.

## Acceptance Criteria

- An unexpected DB read/write failure produces exactly one error line (operation + cause type + cause message); nothing logs it again on the way to the HTTP response.
- Each successful mutation (create/edit/archive/delete a plant or operation; add/edit a substrate component or pesticide) produces exactly one info line (action + id). Reads produce no line on success.
- A failed background recomputation cycle produces exactly one line; the next cycle still runs on schedule.
- Startup logs one info line (listening address + version) once ready, or one error line + non-zero exit if it can't start (bad DB connection, failed migration).
- Every line is a single line. No raw stack traces.

## Out of Scope

- Structured (JSON) or aggregated logging. Logs are read straight from the NAS container's output (`docker logs`/`journalctl`), not through a parser — plain text is what a human reads there, and there's no downstream consumer that would benefit from structure.
- Correlating multiple log lines from one request/cycle with a shared ID.

## Doc Sync

- `CONTRIBUTING.md` — new "Logging" section: effect-owner-only logging, info/error meanings, single-line/no-stack-trace rule.
- `specs/operational.md` — Alerts: single-line log output is the operational signal now (state what is/isn't logged); still no aggregation or alerting.
- `.agents/skills/sdd/SKILL.md` — implementation checklist gains a permanent item: log once, at the effect owner; capability, not a global logger; single line; never inside a pure function.
