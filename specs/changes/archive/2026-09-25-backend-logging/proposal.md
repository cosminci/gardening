# Backend logging

> Standard: [Agentic Engineering Standards](https://github.com/Adobe-AIFoundations/agentic-workflow-standards) v1.2.0.
> Lifetime: open from creation through implementation, archived in the [sync docs & archive step](../../agentic-workflows.md#a-structured-development-skills).

**Date:** 2026-09-25

Add leveled, single-line logging to the backend. There is none today.

## What & Why

- Today: no log output. A persistence failure is a typed result (e.g. `AddFailed(cause)`); every adapter that maps it to an HTTP response discards `cause`. A fatal startup error is an uncaught exception with a multi-line stack trace. A failed background recomputation is silently dropped. Nothing distinguishes "working fine" from "just swallowed an error."
- New: the domain service each action goes through logs its own outcome exactly once — error for an unexpected failure (operation + cause), info for a successful mutation (action + id). One failure produces exactly one log line, regardless of how many layers pass it upward.

## Domain / Design Notes

**Layer: the domain services log, nothing else does.** `PlantJournal`, `PlantAttentionMonitor`, `SubstrateComponentCatalog`, and `PesticideCatalog` are the target — they're the stable contract every caller goes through today (HTTP) and tomorrow (anything else), so a log line stays correct no matter what storage adapter sits underneath it. Persistence adapters (SQL) and HTTP adapters do not log; HTTP already discards the cause when it maps a failure to a status code, and persistence is swappable machinery below the contract, not where the meaning of an action lives.

Hotspots — every public method on those four services, one info line per successful mutation, one error line per unexpected failure, nothing on a successful read:

| Service | Mutations (info + error) | Reads (error-only) |
| --- | --- | --- |
| `PlantJournal` | createPlant, editPlant (including archiving), logOperation, editOperation, deleteOperation | getPlants, getArchivedCount, getOperations, getOperationDateRange |
| `PlantAttentionMonitor` | — | refreshAll (recomputation itself isn't a business mutation, but a per-plant watering level transition — e.g. Current to Overdue — is; refreshAll logs one info line naming only the plants that transitioned and their before/after level, nothing when levels are unchanged) |
| `SubstrateComponentCatalog` | addSubstrateComponent, editSubstrateComponent | getSubstrateComponents |
| `PesticideCatalog` | addPesticide, editPesticide | getPesticides |

The composition root is a second, separate hotspot outside the domain layer: it logs its own startup readiness/failure once. It does not need to log the background recomputation loop itself — `refreshAll` already logs its own failure, on the timer tick and on the same call triggered synchronously after an edit.

Logging is a capability, threaded the same way `Clock` and `IdGenerator` already are — built once in the composition root, passed via `using`, substitutable in tests. Not a global/static logger.

```scala
// before
def make(using store: PlantJournalStore^, idGen: IdGenerator^): PlantJournal^{store, idGen}

// after
def make(using store: PlantJournalStore^, idGen: IdGenerator^, log: Logger^): PlantJournal^{store, idGen, log}
```

Inside `PlantJournal.createPlant` — the mutation case:

```scala
store.addPlant(plant) match
  case AddPlantResult.Added            => log.info(s"plant created id=${plant.id.value}"); CreatePlantResult.Created(plant)
  case AddPlantResult.AddFailed(cause) => log.error(s"add plant failed: ${cause.getClass.getSimpleName}: ${cause.getMessage}")
                                          CreatePlantResult.CreateFailed(cause)
```

Inside `PlantJournal.getPlants` — the read case (today a one-line delegation to the store; it stays a delegation, just no longer a silent one):

```scala
store.getPlants(status) match
  case failure: GetPlantsResult.ReadFailed => log.error(s"get plants failed: ...")
                                               failure
  case success                             => success
```

The HTTP adapter that turns `CreateFailed`/`ReadFailed` into a 500 does **not** log it again — the cause was already logged where it happened.

## Invariants

- HTTP status codes, response bodies, and domain result types are unchanged; logging is a side channel, not a new decision.
- Background recomputation keeps retrying on schedule after a failed cycle; logging a failure doesn't change that.

## Acceptance Criteria

- An unexpected DB read/write failure produces exactly one error line (operation + cause type + cause message); nothing logs it again on the way to the HTTP response.
- Each successful mutation (create/edit/archive/delete a plant or operation; add/edit a substrate component or pesticide) produces exactly one info line (action + id). Reads produce no line on success.
- A failed background recomputation cycle produces exactly one line; the next cycle still runs on schedule.
- A recomputation cycle where one or more plants' watering level (Unavailable/Current/Overdue/RedAlert) changed since the previous cycle produces exactly one info line naming only the transitioned plants and their before/after level. A cycle with no level changes produces no line.
- Startup logs one info line (listening address + version) once ready, or one error line + non-zero exit if it can't start (bad DB connection, failed migration).
- Every line is a single line. No raw stack traces.

## Out of Scope

- Structured (JSON) or aggregated logging. Logs are read straight from the NAS container's output (`docker logs`/`journalctl`), not through a parser — plain text is what a human reads there, and there's no downstream consumer that would benefit from structure.
- Correlating multiple log lines from one request/cycle with a shared ID.

## Doc Sync

These rules bind this change's own implementation via the Acceptance Criteria above; the entries below only make them durable for changes after this one.

- `CONTRIBUTING.md` — new "Logging" section: domain services are the only logging point, info/error meanings, single-line/no-stack-trace rule.
- `specs/operational.md` — Alerts: single-line log output is the operational signal now (state what is/isn't logged); still no aggregation or alerting.
- `.agents/skills/sdd/SKILL.md` — implementation checklist gains a permanent item: log once, at the domain service; capability, not a global logger; single line; never inside a pure function.
