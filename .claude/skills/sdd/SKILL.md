---
name: sdd
description: >-
  Spec-driven development for this repo. Use for every product change — features (planned changes, refactors, migrations) and investigations (bugs, incidents). One skill that forks by change type and drives the classify → spec → tests → implement → sync & archive loop against the Agentic Engineering Standards. Trigger when asked to add a feature, fix a bug, change behaviour, or "write a change spec".
---

# SDD — spec-driven development

Standard: Agentic Engineering Standards v1.2.0. This skill enforces the development loop. It forks between **feature** and **investigation** work, produces a reviewable artifact at every phase, and degrades gracefully when a step can't complete (say what's missing, don't silently skip).

Scale the ceremony to the change: a one-line behaviour tweak needs a short spec and a couple of tests; a new subsystem needs the full treatment. The phases below are always in order; their depth flexes.

## Before you start — create the checklist

Create `.agent-work/<slug>/checklist.md` listing every phase gate below plus every quality-standard item that applies to the artifacts you'll produce. Check items off as you go; before finishing, confirm each is satisfied or explicitly justified. The checklist is your proof of discipline — it is never reviewed and never leaves `.agent-work/`.

## Phase 1 — Classify

Decide **feature** or **investigation** and record it in the checklist.

- **Feature** — a planned change, refactor, or migration. You know the intended new behaviour.
- **Investigation** — a bug or incident. You do not yet know the cause; the spec is a trail toward one.

Gate: the classification is written down and the branch is named `feature/<slug>` or `investigation/<slug>`.

## Phase 2 — Spec (reviewed before any code)

Write `specs/changes/<slug>/proposal.md` from the change-spec template. Keep it at the altitude of behaviour, intent, and the domain contract: the observable behaviour, plus the domain types, ports, and service signatures that define the feature — that contract is design, and belongs in the spec. What stays out is implementation: adapter or library choices, file paths, module layout, private wiring, HTTP endpoint shapes (those live in `contracts.md` / `openapi.yaml`), and diff walkthroughs (see the Change Specs checklist in the standards).

- **Feature:** state current → new behaviour explicitly; acceptance criteria (each externally observable); invariants (pre-existing guarantees that must still hold — not the change's own rules); tradeoffs accepted; and **Doc Sync** — the exact living-doc sections the archive phase will update.
- **Investigation:** record hypotheses and the evidence for or against each, narrowing to a proven root cause. The archived spec's "What & Why" must state the proven root cause and the rejected hypotheses that revealed an expectation-vs-reality gap.

Gate: the spec passes the Change Specs checklist and has been reviewed (a human, or a review pass) before code starts. The `superpowers:brainstorming` and `superpowers:writing-plans` skills are available when the design is non-obvious.

## Phase 3 — Tests projected from the spec

Derive tests from the spec's behaviour and acceptance criteria, before implementation. Never reverse-engineer tests from code — a test must trace to a spec statement, and fail only when a stated behaviour changes. Follow the testing conventions in CONTRIBUTING.md (MUnit / Vitest tiers, naming, mocking). `superpowers:test-driven-development` is available.

Gate: every acceptance criterion and enumerated edge case maps to at least one test; the new tests fail for the right reason before implementation.

## Phase 4 — Implement

Make the tests pass. Zero warnings, 100% coverage, and green gates are not negotiable:

- Backend: `cd backend && sbt compile "scalafixAll --check" scalafmtCheckAll coverage test coverageReport`
- Frontend: `cd frontend && npm run verify`
- Whole change: `dagger call verify` (affected). If you touched the tapir endpoints or `contract/`, regenerate the contract and confirm `dagger call contract-drift`.

Gate: all of the above exit zero. Do not weaken a gate to pass (see CLAUDE.md → What agents must not do).

## Phase 5 — Sync & archive

Apply the spec's Doc Sync to the living docs (`specs/*.md`, and `ci/specs/*.md` if the pipeline changed), so they describe the system as it now is. Structure every living doc against its template in `specs/templates/<name>.md`: add only the sections that template defines, keep each fact in one place (link instead of restating), and omit a section rather than pad it. Then move the spec to `specs/changes/archive/YYYY-MM-DD-<slug>/`.

Gate: every Doc Sync entry is applied; each touched living doc conforms to its template (no out-of-template sections, no duplicated facts); the spec is archived; the checklist is satisfied.
