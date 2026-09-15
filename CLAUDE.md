# CLAUDE.md

> Standard: Agentic Engineering Standards v1.2.0

The engineering standard here is written for everyone, not just agents: [DESIGN-PRINCIPLES.md](DESIGN-PRINCIPLES.md) is how the code is designed and built, and [CONTRIBUTING.md](CONTRIBUTING.md) covers conventions, workflow, and the definition of done. Read those first and hold to them as any engineer would; this file only adds the operational guardrails an agent needs on top. Further orientation: [README.md](README.md) (purpose, commands, repo map), [specs/](specs/) and [ci/specs/](ci/specs/) (how the service and pipeline work), [GLOSSARY.md](GLOSSARY.md) (domain terms), and the SDD skill at [.claude/skills/sdd/SKILL.md](.claude/skills/sdd/SKILL.md).

## How work happens here

All product changes go through the SDD skill (classify → spec → tests → implement → sync & archive). Do not add product behaviour outside a reviewed change spec.

## Gates — each is a stop condition; do not proceed until it holds

- Before calling a component done, its gate exits zero:
  - Backend: `cd backend && sbt compile "scalafixAll --check" scalafmtCheckAll coverage test coverageReport`
  - Frontend: `cd frontend && npm run verify`
  - Pipeline: `cd .dagger && npm run verify`
- Before calling a change done, `dagger call verify` (affected) exits zero. If you touched the tapir endpoints or `contract/`, `dagger call contract-drift` also exits zero.
- Coverage is 100%. If a line cannot be covered, it belongs in an excluded imperative-shell region (see CONTRIBUTING.md) — move it there instead of lowering a threshold.
- `contract/` is generated and read-only. Regenerate it (see README) and commit the result; never hand-edit it.

## What agents must not do

- Do not weaken a gate to make it pass: no lowering coverage thresholds, no `// scalafix:off`, no `eslint-disable`, no widening a formatter/scalafix/coverage exclusion to dodge a finding. If code is hard to test or lint, that is a design signal — fix the design, or move genuine glue into an existing imperative-shell exclusion — never relax the gate (see DESIGN-PRINCIPLES.md §6).
- Do not introduce an effect system (Cats Effect, ZIO). The backend is direct-style on Loom; capabilities are injected with `using`.
- Do not commit secrets. Publishing reads a `write:packages` GitHub PAT as a Dagger Secret (`--token=env:GITHUB_PERSONAL_PAT`); never embed or print it (see [ci/specs/operational.md](ci/specs/operational.md)).
- Do not delete or move `plants/`, `guides/`, `shopping-list.md`, or `GARDEN-GUIDE.md` — they are the import source for a later feature.
