---
name: sdd
description: >-
  Spec-driven development for this repo. Use for every product change — features (planned changes, refactors, migrations) and investigations (bugs, incidents). One skill that forks by change type and drives the classify → spec → tests → implement → sync & archive loop against the Agentic Engineering Standards. Trigger when asked to add a feature, fix a bug, change behaviour, or "write a change spec".
---

# SDD — spec-driven development

Standard: Agentic Engineering Standards v1.2.0. This skill enforces the development loop. It forks between **feature** and **investigation** work, produces a reviewable artifact at every phase, and degrades gracefully when a step can't complete (say what's missing, don't silently skip).

Scale the ceremony to the change: a one-line behaviour tweak needs a short spec and a couple of tests; a new subsystem needs the full treatment. The phases below are always in order; their depth flexes.

## Before you start — create the checklist

Create `.agent-work/<slug>/checklist.md` listing every phase gate below plus every quality-standard item that applies to the artifacts you'll produce. Include the [implementation authoring checklist](#implementation-authoring-checklist) for every change that writes code. Check items off as you go; before finishing, inspect every touched code and test file against each applicable item, then confirm it is satisfied or explicitly justified. The checklist stays in `.agent-work/`; its checked boxes are claims to verify, not proof by themselves.

Before declaring a phase complete, have an independent reviewer fill a separate copy of its applicable checklist from the spec, templates, implementation, and validation evidence **without reading the author's checked copy first**. Reconcile every disagreement by changing the artifact or recording a justified exception, then have the reviewer confirm the result. Keep both copies in `.agent-work/`; never self-approve an unchecked or unreviewed gate.

## Phase 1 — Classify

Decide **feature** or **investigation** and record it in the checklist.

- **Feature** — a planned change, refactor, or migration. You know the intended new behaviour.
- **Investigation** — a bug or incident. You do not yet know the cause; the spec is a trail toward one.

Gate: the classification is written down and the branch is named `feature/<slug>` or `investigation/<slug>`.

## Phase 2 — Spec PR (reviewed and merged before implementation)

Write `specs/changes/<slug>/proposal.md` from the change-spec template. Keep it at the altitude of behaviour, intent, and the domain contract: the observable behaviour, plus the domain types, ports, and service signatures that define the feature — that contract is design, and belongs in the spec. What stays out is implementation: adapter or library choices, file paths, module layout, private wiring, HTTP endpoint shapes (those live in `contracts.md` / `openapi.yaml`), and diff walkthroughs (see the Change Specs checklist in the standards).

- **Feature:** state current → new behaviour explicitly; acceptance criteria (each externally observable); invariants (pre-existing guarantees that must still hold — not the change's own rules); tradeoffs accepted; and **Doc Sync** — the exact living-doc sections the archive phase will update.
- **Investigation:** record hypotheses and the evidence for or against each, narrowing to a proven root cause. The archived spec's "What & Why" must state the proven root cause and the rejected hypotheses that revealed an expectation-vs-reality gap.

The proposal is submitted as its own **Spec PR**. That PR contains the proposal and any
checklist or planning artifacts needed to review intent, but no production implementation.
Implementation must not begin until the Spec PR is reviewed and merged.

Gate: the spec passes the Change Specs checklist and the Spec PR has been reviewed and merged
before code starts. The `superpowers:brainstorming` and `superpowers:writing-plans` skills are
available when the design is non-obvious.

### Spec authoring discipline

Before drafting:

- Read the governing change-spec template from its exact source. Do not recreate it from memory.
- Read the closest approved spec for altitude and density. For domain or port changes, use the archived care-journal spec as the reference.
- Use the governing template for structure; mimic precedent for concision, not obsolete headings or details.

Write each fact once:

- **What & Why:** current behaviour → new behaviour; missing capability and intent.
- **Domain / Design Notes:** changed domain contracts, ports, and boundaries.
- **Acceptance Criteria:** the smallest externally observable proof set, including relevant failure and accessibility outcomes.
- **Doc Sync:** only the living-document sections that must change.

Keep the proposal proportional:

- Start with required sections. Add an optional section only when it contributes new information.
- Use short technical bullets. Avoid narrative paragraphs and introductory filler.
- Do not restate What & Why or Domain / Design Notes as acceptance criteria.
- Group cohesive outcomes into one criterion; do not create a criterion per sentence or implementation branch.
- Compare the final proposal with the closest approved spec. If a small change approaches a foundational spec's size or criterion count, cut it.

Gate: every sentence has one section that owns it; removing any sentence would lose information.

### Change-spec template

Use this structure for `specs/changes/<slug>/proposal.md`. The `What & Why`,
`Acceptance Criteria`, and `Doc Sync` sections are required. Include the other sections
only when they carry useful, non-duplicated information. High-level domain and design notes use a dedicated `Domain / Design Notes` section whenever
the change evolves domain contracts, ports, or system boundaries; otherwise they belong in
`What & Why`, as in the original care-journal spec. Keep that section at contract altitude: ports, core
domain evolution, and boundary responsibilities are allowed, but not implementation
walkthroughs. Do not add free-floating risk or open-question sections.

```markdown
# <Title>

> Standard: [Agentic Engineering Standards](https://github.com/Adobe-AIFoundations/agentic-workflow-standards) v<version>.
> Lifetime: open from creation through implementation, archived in the [sync docs & archive step](../../agentic-workflows.md#a-structured-development-skills).

<!-- Boundary clarifications or risk callouts attach inline to the section they qualify. -->

**Date:** <YYYY-MM-DD>

<!-- One sentence describing the change. -->

## What & Why

<!-- State current behavior → new behavior and why. Include domain evolution, ports,
core contracts, and boundary design notes here when applicable. Keep it at behavior,
intent, and domain-contract altitude: no file paths, class names, private wiring,
implementation walkthroughs, or endpoint/schema field details. -->

## Domain / Design Notes

<!-- Required when the change evolves domain contracts, ports, service contracts, or
backend/frontend boundaries. Otherwise omit it. This is a little "how", but must
remain at contract altitude: no file paths, class names, private wiring, library
choices, or diff walkthroughs. -->

## Alternatives Considered

<!-- Optional. Include only real alternatives and why they were rejected. -->

## Invariants

<!-- Optional. Pre-existing guarantees that must remain true. Use pass/fail language.
The change's own rules belong in Acceptance Criteria. -->

- <invariant>

## Tradeoffs Accepted

<!-- Optional. State what becomes worse or more constrained and why it is acceptable. -->

## Acceptance Criteria

<!-- Required. Each item must be externally observable or testable. Include fallback,
rollback, loading, failure, boundary, and accessibility behavior where relevant. -->

- <criterion>

## Doc Sync

<!-- Required. Name each affected living document and the section or property to update.
Omit only when no living document changes. -->

- <doc> — <section or property that changes>

## Out of Scope

<!-- Optional. Maximum two bullets in functional/business language. Omit if unnecessary. -->
```

## Phase 3 — Tests projected from the spec

Derive tests from the spec's behaviour and acceptance criteria, before implementation. Review the owning suites as projections of each module's complete supported use cases, using the implementation authoring checklist below; change the projection when behavior changes. Never reverse-engineer tests from code — a test must trace to a spec statement, and fail only when a stated behaviour changes. Follow the testing conventions in CONTRIBUTING.md (MUnit / Vitest tiers, naming, mocking). `superpowers:test-driven-development` is available.

Gate: every acceptance criterion and enumerated edge case maps to at least one test; changed tests fail for the right reason before implementation.

## Phase 4 — Implementation PR(s)

After the Spec PR is merged, implement the approved behavior in one or more **Implementation
PRs**. Each implementation PR stays within the approved spec, links back to it, and can be
reviewed and validated independently. Split the implementation into multiple PRs when the
change has separable seams or would otherwise exceed one focused review session; do not split
to bypass quality gates.

Make the tests pass. Zero warnings, 100% coverage, and green gates are not negotiable:

- Backend: `cd backend && sbt compile "scalafixAll --check" scalafmtCheckAll coverage test coverageReport`
- Frontend: `cd frontend && npm run verify`
- Whole change: `dagger call verify` (affected). If you touched the tapir endpoints or `contract/`, regenerate the contract and confirm `dagger call contract-drift`.

Gate: all of the above exit zero for the complete implementation. Do not weaken a gate to pass
(see CLAUDE.md → What agents must not do).

### Implementation authoring checklist

Before declaring any code implementation complete, add these items to the change checklist and
review every touched code and test file against them. An item may be marked not applicable only
when the checklist records why.

- [ ] Keep assertions to one physical line in the normal case. Extract clearly named
  `expectedX` and `actualX` values to do so; further decompose deeply nested expressions into
  values when needed.
- [ ] Omit return types from private members unless the member returns `Unit`.
- [ ] Model tests as small use cases through the trait under test, not as implementation details.
  A small number of use cases should cover the dominant behavior; add exceptional cases only when
  they represent real user or domain behavior, and fold them into an existing use case when that
  makes the behavior clearer.
- [ ] Review each affected suite as a whole against the module's supported behavior. Edit, extend,
  simplify, merge, or remove existing tests as the use cases evolve; add a test only when the
  behavior is not already represented. Do not append cases just to chase coverage.
- [ ] Order tests, fields, methods, and other declarations from top to bottom and left to right by
  semantic importance and value.
- [ ] Use this test-suite shape without exception: reusable, non-trivial mock data first; use-case
  tests second; then `Ref` values and `buildX` helpers, where `X` is the tested trait.
- [ ] In backend component and HTTP seam tests, configure and observe collaborators through `Refs`;
  call `buildX(refs)` with no other arguments. Construct port substitutes inside that builder,
  not in test use cases or separate stub classes.
- [ ] Prefer codecs that encode a wire format directly over DTOs. Introduce a DTO only when it
  cannot leak beyond its boundary and a codec cannot express the format cleanly.
- [ ] Keep test helpers to a minimum. A test should be readable as a use case and normally need
  only its `buildX` call to set up its harness.
- [ ] Use Cats syntax where it expresses the value directly, such as `.some` and `.asRight`.
- [ ] Use named arguments only when the value alone does not make its role clear.
- [ ] Model invalid states out of the domain. A case class must not permit nonsensical field
  combinations.
- [ ] Use coverage exclusions only for `app.*`, OpenAPI document generation, documented
  scoverage bugs, or genuinely nonsensical adapter implementation paths. Domain use cases must
  make impossible states unrepresentable rather than excluding them.
- [ ] Name values for the meaning they establish, not merely the helper call that produced them.
  Prefer `wateredPlantOperations` over `watered` when the value describes a vector of watered
  care operations; make construction arguments equally self-describing.
- [ ] Declare fixtures in semantic scenario order, grouping all values that describe one state or
  use case together instead of grouping values by their type.
- [ ] Interpolate semantically meaningful domain values into test names so type or state renames
  keep descriptions correct; for example, use `s"should return ${WateringAttention.Current} ..."`
  rather than spelling `Current` in the name.
- [ ] Put application logic behind a trait named for its behavior, with an identically named
  companion that exposes `make` and a private `Live<TraitName>` implementation. Tests exercise
  the trait produced by `make`, never the private implementation.
- [ ] Let integration tests own their resource lifecycle. A builder constructs a resource only;
  each use case explicitly acquires, uses, and releases it through the ecosystem's standard
  resource primitive. Never pass a test body into a callback-style setup helper.
- [ ] Structure every test as Arrange, Act, Assert: declare fixtures first; then references,
  build the trait under test, and execute the behavior; finally declare expected values and make
  assertions. Separate Arrange, Act, and Assert with mandatory blank lines.

## Phase 5 — Archive and living-doc PR

After all Implementation PRs are merged, submit a separate **Archive + Living Docs PR**.
Apply the spec's Doc Sync to the living docs (`specs/*.md`, and `ci/specs/*.md` if the
pipeline changed), so they describe the system as it now is. Structure every living doc against
its template in `specs/templates/<name>.md`: add only the sections that template defines, keep
each fact in one place (link instead of restating), and omit a section rather than pad it. Move
the spec to `specs/changes/archive/YYYY-MM-DD-<slug>/` in this PR.

The Archive + Living Docs PR links the merged Spec PR and all Implementation PRs. It contains no
new product behavior; it closes the documentation and archival work only.

Gate: every Doc Sync entry is applied; each touched living doc conforms to its template (no
out-of-template sections, no duplicated facts); the spec is archived; the Archive + Living Docs
PR is reviewed and merged; and the checklist is satisfied.
