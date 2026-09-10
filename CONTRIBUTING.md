# Contributing

> Standard: Agentic Engineering Standards v1.2.0

Machine-enforceable style lives in the tool configs (scalafmt, scalafix + WartRemover, ESLint,
Prettier, dependency-cruiser) and is not repeated here. This document covers the judgment calls a
reviewer makes and the workflow every change follows.

## Development workflow — SDD

Every change goes through the **SDD skill** at
[`.claude/skills/sdd/SKILL.md`](.claude/skills/sdd/SKILL.md) — spec-driven development, one skill
that forks by change type:

- **Feature** (planned change, refactor, migration): a change spec states current → new behavior,
  acceptance criteria, invariants, and tradeoffs; then tests projected from the spec; then
  implementation.
- **Investigation** (bug, incident): a hypotheses-and-evidence trail leads to a proven root cause;
  the archived spec records the root cause and the rejected hypotheses.

Its five phases are **classify → spec → tests → implement → sync & archive**. The spec is reviewed
before code is written. The final phase syncs the living docs and archives the change spec.

- **Specs live in-repo** under [`specs/changes/<slug>/proposal.md`](specs/changes/), archived to
  `specs/changes/archive/YYYY-MM-DD-<slug>/`. The living docs are
  `specs/{design,contracts,testing,operational}.md`; the pipeline's are `ci/specs/`.
- **The work directory** for checklists and investigation trails is `.agent-work/` (git-ignored,
  never reviewed).

## Branching & commits

- One branch per change: `feature/<slug>` or `investigation/<slug>` (e.g. `feature/add-plant`).
- Commit subjects are imperative and ≤72 characters, with no ticket prefix (this repo has no
  tracker). The body explains *why* when the diff does not.

## Pull requests

- Size a PR for a single focused review session — split on ease of reviewing, not on line count.
  When a change is large enough that reviewing intent and code together would exceed one session,
  the spec is its own PR ahead of the implementation PR.
- A PR references its change spec and calls out what the reviewer should focus on.
- Automated review runs on every PR; a human approves intent and domain correctness, since the
  gates already catch mechanical issues.

## Testing conventions

- **Backend** uses MUnit; name suites `<Unit>Suite`. Test behaviour through the domain and the
  capability ports, never implementation details, so a test fails only when a stated behaviour
  changes. Use a real in-memory SQLite for persistence-adapter tests (a seam test); substitute the
  capability traits (`Clock`, and `Database` via a `DatabaseProbe` stub) for application tests.
- **Frontend and pipeline** use Vitest with a tiered file-name convention: `*.componentTest.ts(x)`
  (one unit in isolation, boundaries stubbed), `*.seamIntegrationTest.ts(x)` (across one real
  seam), and `*.systemIntegrationTest.ts(x)` (the running system).
- Prefer duplication over a shared test helper until the third repetition — a test should read
  top-to-bottom without indirection.
- Coverage is enforced at 100%. The only regions excluded are the imperative shells with no
  branching logic: the backend composition root, HTTP transport, and endpoint/DTO declarations;
  the frontend composition root (`main.tsx`) and port interfaces (`domain/ports.ts`); and the
  pipeline entrypoint (`index.ts`), its `hooks/**`, and `buildEnv.ts`. Put untestable glue in one
  of these regions rather than lowering a threshold.

## Definition of done

A change is done when its change spec is written and reviewed; tests projected from the spec pass;
the affected component gates are green (`dagger call verify`); coverage thresholds hold; the living
docs named in the spec's Doc Sync are updated; and the change spec is archived.
