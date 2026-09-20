# Contributing

> Standard: Agentic Engineering Standards v1.2.0

How the code is designed — Ports & Adapters, DDD, Fractal Design, ACLs, Indirection Layers, and the reasoning behind the strict build — lives in [DESIGN-PRINCIPLES.md](DESIGN-PRINCIPLES.md); read it first, since the conventions below follow from it. Machine-enforceable style lives in the tool configs (scalafmt, scalafix + WartRemover, ESLint, Prettier, dependency-cruiser) and is not repeated here. Markdown prose is soft-wrapped — one line per paragraph, no manual line breaks — so it reflows to the reader's width; Prettier's `proseWrap: never` enforces it. This document covers the judgment calls a reviewer makes and the workflow every change follows.

## Working in the repo

**Stack.** A Scala 3 backend (direct style, no effect system) on Java 25 + Loom — tapir (sync/Netty) over Magnum + SQLite; a SolidJS + TypeScript frontend built with Vite; the HTTP contract single-sourced from the backend's tapir endpoints to OpenAPI, with the TypeScript client generated from it (`contract/` is committed and read-only); CI as a TypeScript Dagger module (`.dagger/`) with an affected-component selector; and packaging as one slim, non-root runtime image serving the API and the built frontend.

**Setup.** Install the pinned toolchain (Java 25, Scala 3.8, Node LTS) with [mise](https://mise.jdx.dev): `mise install`. Docker is required for the Dagger pipeline and the image build. The commands below assume mise is shell-activated; otherwise prefix them with `mise exec --`.

**Exposure.** Runs on the home NAS, reachable over LAN and Tailscale only, with no application authentication; no secrets are committed.

| Task | Command |
| --- | --- |
| Backend gate | `cd backend && sbt compile "scalafixAll --check" scalafmtCheckAll coverage test coverageReport` |
| Run the backend | `cd backend && sbt run` |
| Frontend gate | `cd frontend && npm run verify` |
| Frontend dev server | `cd frontend && npm run dev` |
| Pipeline gate | `cd .dagger && npm run verify` |
| Regenerate the contract | `cd backend && sbt "runMain gardening.app.GenerateOpenApi ../contract/openapi.yaml" && cd ../contract && npm run generate` |
| Affected checks (local / pre-push) | `dagger call verify` |
| All checks | `dagger call verify --all` |
| Build the runtime image | `dagger call build-image` |

| Path | What |
| --- | --- |
| `backend/` | Scala 3 service — `domain`, `capabilities` (ports), `adapters`, `app`. |
| `frontend/` | SolidJS single-page app. |
| `contract/` | Generated OpenAPI document and TypeScript client (read-only). |
| `.dagger/` | TypeScript Dagger CI module. |
| `specs/` | Living design / contracts / testing / operational docs, and change specs. |
| `ci/specs/` | The pipeline's own docs. |
| `.claude/skills/sdd/` | The spec-driven development skill. |
| `plants/`, `guides/`, `shopping-list.md`, `GARDEN-GUIDE.md` | Import source for a later feature, not part of the app. |

## Documentation style

- Short, technical, concise — bullet lists over multi-sentence paragraphs.
- Treat every word as a cost; cut hedging, filler, and restatement.
- Never repeat a fact — state it once, in one place.

## Naming

- Optional fields, vals, and params are prefixed `maybe` — `maybeNickname: Option[Nickname]`.
- `Either`-typed fields, vals, and params are suffixed `Result` — `editResult: Either[…, …]`.
- Name an argument when its value does not reveal its role at the call site — for example `maybeNote = None`, `date = Instant.parse(…)`, or `share = 100`. Keep self-describing variables and value wrappers positional.

## Scala composition

- Trait methods return explicit result ADTs, never `Either`. Adapt those results to `Either` only inside a concrete implementation when dependent steps need composition.
- Keep business orchestration visible in the public service method. Similar workflows should have visibly similar structure; do not hide their ordering or compensation inside helpers.
- Give each helper one responsibility and return the narrowest neutral type that describes it. A persistence or domain-step helper must not construct the caller's public result ADT.
- Adapt boundary ADTs to `Either` when several dependent steps need composition. Do not wrap a single result in `Either` only to unwrap it immediately.
- Keep `for` comprehensions linear. Do not nest `flatMap`, matches, or multi-level conditionals inside them; extract a meaningful step or model the additional state explicitly.
- Model success, expected short-circuiting, and failure as distinct states when all three exist. Do not misrepresent a successful no-op as an error merely to short-circuit an `Either`.
- Attach compensation in the owning workflow, immediately beside the step it compensates. Run it lazily only after that step fails, preserve the primary failure when compensation succeeds, and retain both failures when compensation also fails.
- Design ADTs so impossible states are unrepresentable. Prefer a failure subtype over accepting a success-or-failure result and adding an unreachable branch.
- Never destructure a product merely to inspect one or two members. Match the relevant member directly and access other values by name so adding a field does not break unrelated patterns.
- Prefer optics for focused updates through nested immutable domain values rather than nested `copy` calls.

## Development workflow — SDD

Every change goes through the **SDD skill** at [`.claude/skills/sdd/SKILL.md`](.claude/skills/sdd/SKILL.md) — spec-driven development, one skill that forks by change type:

- **Feature** (planned change, refactor, migration): a change spec states current → new behavior, acceptance criteria, invariants, and tradeoffs; then tests projected from the spec; then implementation.
- **Investigation** (bug, incident): a hypotheses-and-evidence trail leads to a proven root cause; the archived spec records the root cause and the rejected hypotheses.

Its five phases are **classify → spec → tests → implement → sync & archive**. The spec is reviewed before code is written. The final phase syncs the living docs and archives the change spec.

- **Specs live in-repo** under [`specs/changes/<slug>/proposal.md`](specs/changes/), archived to `specs/changes/archive/YYYY-MM-DD-<slug>/`. The living docs are `specs/{design,contracts,testing,operational}.md` (the pipeline's are `ci/specs/`); each follows a strict template in [`specs/templates/`](specs/templates/) — one fact in one place, empty sections omitted — so they stay lean as the app grows.
- **The work directory** for checklists and investigation trails is `.agent-work/` (git-ignored, never reviewed).

## Branching & commits

- One branch per change: `feature/<slug>` or `investigation/<slug>` (e.g. `feature/add-plant`).
- Commit subjects are imperative and ≤72 characters, with no ticket prefix (this repo has no tracker). The body explains _why_ when the diff does not.

## Pull requests

- Size a PR for a single focused review session — split on ease of reviewing, not on line count. When a change is large enough that reviewing intent and code together would exceed one session, the spec is its own PR ahead of the implementation PR.
- A PR references its change spec and calls out what the reviewer should focus on.
- Automated review runs on every PR; a human approves intent and domain correctness, since the gates already catch mechanical issues.

## Testing conventions

- **Backend** uses MUnit; name suites `<Unit>Suite`. Test behaviour through the domain and the capability ports, never implementation details, so a test fails only when a stated behaviour changes. Substitute the capability ports for domain and application tests; exercise a persistence adapter against a real in-memory SQLite (a seam test); and prove an HTTP adapter that carries logic by driving its endpoints over a stub of the service it delegates to — never by reaching past that service to a lower port.
- **Frontend and pipeline** use Vitest with a tiered file-name convention: `*.componentTest.ts(x)` (one unit in isolation, boundaries stubbed), `*.seamIntegrationTest.ts(x)` (across one real seam), and `*.systemIntegrationTest.ts(x)` (the running system).
- Every test name starts with `should ` and describes a use case, not an implementation detail.
- Prefer duplication over a shared test helper until the third repetition — a test should read top-to-bottom without indirection.
- Coverage is enforced at 100% — as a means, not a goal (see DESIGN-PRINCIPLES.md §6). Hard-to-test code is a design signal, not a licence to hack the test or lower a threshold. The HTTP layer is tested at its seam, not excluded: logic-bearing endpoints through the tapir stub interpreter and static serving against a live server. The only file or package exclusions are composition roots that just wire already-tested parts together: the backend's (`gardening.app.*`), the frontend's (`main.tsx`), and the pipeline entrypoint (`index.ts`) with its `hooks/**` and `buildEnv.ts`. Adapters that carry logic — request mapping, error translation, persistence — are never excluded. Exclude only a genuine wiring shell; never contort a test to reach one.
- A narrowly scoped `$COVERAGE-OFF$` / `$COVERAGE-ON$` exclusion is permitted for an unreachable branch when an adjacent comment names the domain invariant that makes it unreachable. Never exclude a reachable failure mode or the enclosing method.

## Definition of done

A change is done when its change spec is written and reviewed; tests projected from the spec pass; the affected component gates are green (`dagger call verify`); coverage thresholds hold; the living docs named in the spec's Doc Sync are updated; and the change spec is archived.
