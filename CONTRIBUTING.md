# Contributing

> Standard: Agentic Engineering Standards v1.2.0

How the code is designed — Ports & Adapters, DDD, Fractal Design, ACLs, Indirection Layers, and the reasoning behind the strict build — lives in [DESIGN-PRINCIPLES.md](DESIGN-PRINCIPLES.md); read it first, since the conventions below follow from it. Machine-enforceable style lives in the tool configs (scalafmt, scalafix + WartRemover, ESLint, Prettier, dependency-cruiser) and is not repeated here. Markdown prose is soft-wrapped — one line per paragraph, no manual line breaks — so it reflows to the reader's width; Prettier's `proseWrap: never` enforces it. This document covers the judgment calls a reviewer makes and the workflow every change follows.

## Working in the repo

**Stack.** A Scala 3 backend (direct style, no effect system) on Java 25 + Loom — tapir (sync/Netty) over Magnum + SQLite; a SolidJS + TypeScript frontend built with Vite; the HTTP contract single-sourced from the backend's tapir endpoints to OpenAPI, with the TypeScript client generated from it (`contract/` is committed and read-only); CI as a TypeScript Dagger module (`.dagger/`) with an affected-component selector; and packaging as one slim, non-root runtime image serving the API and the built frontend.

**Setup.** Install the pinned toolchain (Java 25, Scala 3.8, Node LTS) with [mise](https://mise.jdx.dev): `mise install`. Docker is required for the Dagger pipeline and the image build. The commands below assume mise is shell-activated; otherwise prefix them with `mise exec --`.

**Exposure.** Runs on the home NAS, reachable over LAN and Tailscale only, with no application authentication; no secrets are committed.

### Local development

Prerequisites: Python 3, mise-managed Java 25, sbt, Node and npm; free localhost ports 8080 and 5173. Install frontend dependencies once:

```sh
mise install
cd frontend && npm ci && cd ..
```

Start both services with a persistent, NAS-independent journal in ignored `.local/`:

```sh
mise exec -- python3 scripts/local.py
```

Open `http://127.0.0.1:5173`. Vite proxies API requests to the local backend; Ctrl-C stops both. Override occupied ports with `GARDENING_PORT` and `GARDENING_DEV_PORT`.

To seed or refresh from the NAS, configure SSH authentication locally; the NAS needs `sqlite3`. From the repository root:

```sh
GARDENING_NAS_SSH=tower.lan \
GARDENING_NAS_DB_PATH=/mnt/user/appdata/plant-journal/gardening.db \
mise exec -- python3 scripts/local.py --refresh
```

An existing local journal requires typing `replace` to discard local edits (`--yes` confirms non-interactively). On failure, check SSH access, the source path and NAS `sqlite3`; retry or start without `--refresh` to keep the previous journal. See [runtime dependencies](specs/operational.md#runtime-dependencies) for snapshot semantics.

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
| `.agents/skills/` | Shared coding-agent skills, including spec-driven development. |
| `plants/`, `guides/`, `shopping-list.md`, `GARDEN-GUIDE.md` | Import source for a later feature, not part of the app. |

## Documentation style

- Short, technical, concise — bullet lists over multi-sentence paragraphs.
- Treat every word as a cost; cut hedging, filler, and restatement.
- Never repeat a fact — state it once, in one place.
- Integrate new lessons into the rule that owns them. Refine or replace stale guidance instead of appending a chronology of discoveries.

## Naming

- Optional fields, vals, and params are prefixed `maybe` — `maybeNickname: Option[Nickname]`.
- `Either`-typed fields, vals, and params are suffixed `Result` — `editResult: Either[…, …]`.
- Name an argument when its value does not reveal its role at the call site — for example `maybeNote = None`, `date = Instant.parse(…)`, or `share = 100`. Keep self-describing variables and value wrappers positional.
- In TypeScript, use named imports only when at most three names fit on one line; otherwise use a namespace import. Keep framework imports named when compiler, lint, or introspection semantics depend on the imported bindings, as Solid does for reactive primitives and control flow and Dagger does for decorators.

## Composition and abstraction

- Apply [Locality of Behavior and knowledge-based DRY](DESIGN-PRINCIPLES.md#3-fractal-design): deduplicate a rule or contract whose divergence would be a bug, not code that merely has the same shape today. Keep genuinely independent use cases local even when that means repeated lines.
- Before extracting shared code, name the responsibility it owns and the callers that must obey the same invariant. If the extraction mainly replaces straightforward code with callbacks, options, or configuration, keep the behavior local.
- An interactive component owns focus with its visual state. Expansion, collapse, success, and failure transitions leave focus on a persistent control or the rendered status; replacing a control must not drop focus to the document.

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

## Logging

- Only the five domain services (`Plants`, `Operations`, `PlantAttentionMonitor`, `SubstrateCatalog`, `PesticideCatalog`) log; adapters (persistence, HTTP) never do, since HTTP already discards the cause when it maps a failure to a status code and persistence is swappable machinery below the logged contract.
- `Logger` is a capability threaded like `Clock` and `IdGenerator` — built once in `Main`, resolved implicitly (`using log: Logger^`) rather than named at every call site, and substituted in tests via `TestImplicits`.
- Info logs a successful mutation (action + id) or a meaningful state transition (e.g. a plant's watering level changing); error logs an unexpected failure (operation + cause). A successful read logs nothing.
- Every line is a single line — never a raw stack trace.

## Development workflow — SDD

Every change goes through the **SDD skill** at [`.agents/skills/sdd/SKILL.md`](.agents/skills/sdd/SKILL.md) — spec-driven development, one skill that forks by change type:

- **Feature** (planned change, refactor, migration): a change spec states current → new behavior, acceptance criteria, invariants, and tradeoffs; then tests projected from the spec; then implementation.
- **Investigation** (bug, incident): a hypotheses-and-evidence trail leads to a proven root cause; the archived spec records the root cause and the rejected hypotheses.

Its five phases are **classify → spec → tests → implement → sync & archive**. The workflow is
split into three PR stages: a **Spec PR** containing only the reviewed proposal, one or more
**Implementation PRs** after the Spec PR merges, and a final **Archive + Living Docs PR** after
all implementation PRs merge. The final PR only archives the proposal and synchronizes living
documentation; it introduces no new product behavior.

- **Specs live in-repo** under [`specs/changes/<slug>/proposal.md`](specs/changes/), archived to `specs/changes/archive/YYYY-MM-DD-<slug>/`. The living docs are `specs/{design,contracts,testing,operational}.md` (the pipeline's are `ci/specs/`); each follows a strict template in [`specs/templates/`](specs/templates/) — one fact in one place, empty sections omitted — so they stay lean as the app grows.
- **The work directory** for checklists and investigation trails is `.agent-work/` (git-ignored, never included in a PR). An independent reviewer fills a separate checklist before each phase is declared complete; see the SDD skill for the review protocol.

## Branching & commits

- One branch per change: `feature/<slug>` or `investigation/<slug>` (e.g. `feature/add-plant`).
- Commit subjects are imperative and ≤72 characters, with no ticket prefix (this repo has no tracker). The body explains _why_ when the diff does not.

## Pull requests

- Every change uses the three-stage SDD PR sequence: Spec PR → Implementation PR(s) → Archive + Living Docs PR.
- The Spec PR must merge before implementation begins. The Archive + Living Docs PR must wait until all implementation PRs merge.
- Size each Implementation PR for a single focused review session — split on separable seams, not to bypass quality gates.
- A PR references its change spec and calls out what the reviewer should focus on.
- Automated review runs on every PR; a human approves intent and domain correctness, since the gates already catch mechanical issues.

## Testing conventions

- **Backend** uses MUnit; name suites `<Component>ComponentTest`. Every suite owns a real runtime trait or port (or a concrete adapter at that seam), never a result ADT, helper, or isolated method. Test behaviour through the owning boundary, never implementation details, so a test fails only when a stated behaviour changes. Substitute capability ports for domain and application component tests; exercise a persistence adapter against a real in-memory SQLite (a seam test); and prove an HTTP adapter that carries logic by driving its endpoints over a stub of the service it delegates to—never by reaching past that service to a lower port.
- **Frontend and pipeline** use Vitest with a tiered file-name convention: `*.componentTest.ts(x)` (one unit in isolation, boundaries stubbed), `*.seamIntegrationTest.ts(x)` (across one real seam), and `*.systemIntegrationTest.ts(x)` (the running system).
- Every test name starts with `should ` and states one domain outcome in the vocabulary exposed by the tested boundary. Do not name an implementation threshold, transition between independent calls, private traversal, or coverage branch when the use case is the resulting behavior.
- A backend component or HTTP seam suite uses `Refs` only for mutable observations of collaborator calls and effects. Pass fixed stub responses, failures, clocks, and other collaborator configuration directly to `buildX(refs, ...)`, with defaults for the ordinary case; call `buildX()` without `Refs` when only the returned result is asserted. The builder creates every collaborator substitute and returns only the component under test; tests assert the observed effects in `Refs`, never mock responses stored there.
- Keep domain setup in the test that uses it. Construct domain values and short sequences explicitly with semantic local names; do not hide case-class construction, expected results, or a one-line `tabulate`/collection expression behind fixture helpers. Prefer local duplication when the values represent independent use cases.
- Compare complete values at the tested boundary. Build a semantically named expected value immediately above a one-line assertion instead of extracting fields or wrapping assertions in helpers.
- Name complex inputs before a call so builder invocations and assertions stay on one line. A test should read top-to-bottom as setup, one boundary action, and the complete outcome without scrolling to decode helpers.
- Coverage is enforced at 100% — as a means, not a goal (see DESIGN-PRINCIPLES.md §6). Hard-to-test code is a design signal, not a licence to hack the test or lower a threshold. The HTTP layer is tested at its seam, not excluded: logic-bearing endpoints through the tapir stub interpreter and static serving against a live server. The only file or package exclusions are composition roots that just wire already-tested parts together: the backend's (`gardening.app.*`), the frontend's (`main.tsx`), and the pipeline entrypoint (`index.ts`) with its `hooks/**` and `buildEnv.ts`. Adapters that carry logic — request mapping, error translation, persistence — are never excluded. Exclude only a genuine wiring shell; never contort a test to reach one.
- A narrowly scoped `$COVERAGE-OFF$` / `$COVERAGE-ON$` exclusion is permitted for output-only adapter callbacks, OpenAPI-only mappings, or unreachable branches. The comment immediately above the standalone `$COVERAGE-OFF$` line must explain why that exact expression cannot run (including the domain invariant for an impossible branch); Scala's coverage compiler requires the marker on its own line. Never exclude a reachable failure mode or the enclosing method.

## Definition of done

A change is done when its change spec is written and reviewed; tests projected from the spec pass; the affected component gates are green (`dagger call verify`); coverage thresholds hold; the living docs named in the spec's Doc Sync are updated; and the change spec is archived.
