# CLAUDE.md

> Standard: Agentic Engineering Standards v1.2.0

Agent guardrails for this repo. Orientation lives elsewhere — read it there rather than here:
[README.md](README.md) (purpose, commands, repo map), [CONTRIBUTING.md](CONTRIBUTING.md)
(conventions, workflow, definition of done), [specs/](specs/) and [ci/specs/](ci/specs/) (how the
service and pipeline work), [GLOSSARY.md](GLOSSARY.md) (domain terms), and the SDD skill at
[.claude/skills/sdd/SKILL.md](.claude/skills/sdd/SKILL.md).

## How work happens here

All product changes go through the SDD skill (classify → spec → tests → implement → sync &
archive). Do not add product behaviour outside a reviewed change spec.

## Gates — each is a stop condition; do not proceed until it holds

- Before calling a component done, its gate exits zero:
  - Backend: `cd backend && sbt compile "scalafixAll --check" scalafmtCheckAll coverage test coverageReport`
  - Frontend: `cd frontend && npm run verify`
  - Pipeline: `cd .dagger && npm run verify`
- Before calling a change done, `dagger call verify` (affected) exits zero. If you touched the
  tapir endpoints or `contract/`, `dagger call contract-drift` also exits zero.
- Coverage is 100%. If a line cannot be covered, it belongs in an excluded imperative-shell region
  (see CONTRIBUTING.md) — move it there instead of lowering a threshold.
- `contract/` is generated and read-only. Regenerate it (see README) and commit the result; never
  hand-edit it.

## What agents must not do

- Do not weaken a gate to make it pass: no lowering coverage thresholds, no `// scalafix:off`, no
  `eslint-disable`, no adding files to a formatter/scalafix exclude list. The single existing
  exclusion — `backend/src/main/scala/gardening/capabilities/Database.scala` from scalafmt and
  scalafix — exists only because those scalameta-based tools cannot parse capture-checking `^`
  syntax. Do not extend it to other files.
- Do not use capture-checking `^` syntax outside the capability wrapper and domain-purity files
  that already opt into it; adding it elsewhere breaks scalafmt/scalafix parsing.
- Do not introduce an effect system (Cats Effect, ZIO). The backend is direct-style on Loom;
  capabilities are injected with `using`.
- Do not commit secrets, and do not create the GHCR publish token. If a release needs it and it is
  absent, stop and ask (see [ci/specs/operational.md](ci/specs/operational.md)).
- Do not delete or move `plants/`, `guides/`, `shopping-list.md`, or `GARDEN-GUIDE.md` — they are
  the import source for a later feature.
