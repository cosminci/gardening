---
name: sdd
description: >-
  Spec-driven development for this repo. Use for every product change — features (planned changes, refactors, migrations) and investigations (bugs, incidents). Classifies the change, then sequences classify → (spike) → spec → tests → implement → sync & archive against the Agentic Engineering Standards. Trigger when asked to add a feature, fix a bug, change behaviour, or "write a change spec".
---

# SDD — spec-driven development

Standard: Agentic Engineering Standards v1.2.0. This file is the orchestrator: it classifies the change and sequences the phases below. [`executor.md`](executor.md) does each phase's work; [`reviewer.md`](reviewer.md) independently gates it. Scale ceremony to the change — a one-line behavior tweak needs a short spec and a couple of tests, a new subsystem needs the full treatment — but the phase order and every gate below are non-negotiable.

## Setup

Before Phase 1, create `.agent-work/<slug>/checklist.md` listing every gate below plus the full text of each checklist that will apply. Check items off as you go. A checked box is a claim to verify, not proof by itself — see [`reviewer.md`](reviewer.md) for how it gets verified.

## Phases

1. **Classify** — `feature` or `investigation`; branch named to match. Per AGENTS.md, a spec is required only when the change adds or revises knowledge a living doc should record; only the maintainer may waive that requirement, explicitly. Decide too whether the problem and its solution are already understood well enough to spec directly, or whether the space needs a spike first. Gate: the classification, the spec requirement (or waiver), and the spike decision are recorded.
2. **Spike** — only when Classify found the problem or solution space unclear; skip straight to Spec PR otherwise. Explore by actually trying to solve it — throwaway code, a prototype of the hard part, working an algorithm by hand — until the domain shape, the ports, and the real edge cases are known, not guessed. Gate: findings are recorded in `.agent-work/<slug>/` and carried into the spec; nothing from this phase is required to pass `AGENTS.md`'s gates or to survive into the Implementation PR.
3. **Spec PR** — reviewed and merged before any implementation starts. Gate: [`checklists/spec-quality.md`](checklists/spec-quality.md) passes and the PR is merged.
4. **Tests** — projected from the merged spec, before implementation. Gate: every acceptance criterion and enumerated edge case maps to a test; changed tests fail for the right reason first.
5. **Implementation PR(s)** — one or more, each within the approved spec. Gate: every gate in `AGENTS.md` exits zero; [`checklists/code-quality.md`](checklists/code-quality.md) and [`checklists/implementation-completeness.md`](checklists/implementation-completeness.md) (this PR's slice) pass.
6. **Archive + Living Docs PR** — after every Implementation PR has merged; introduces no new product behavior. Gate: every `Doc Sync` entry applied verbatim; [`checklists/implementation-completeness.md`](checklists/implementation-completeness.md) passes against the complete merged change; the spec is moved to `specs/changes/archive/YYYY-MM-DD-<slug>/`.

Phase detail: [`executor.md`](executor.md). Review protocol: [`reviewer.md`](reviewer.md). Change-spec template: [`templates/change-spec.md`](templates/change-spec.md). `superpowers:brainstorming`, `superpowers:writing-plans`, and `superpowers:test-driven-development` are available when a design or test set is non-obvious.
