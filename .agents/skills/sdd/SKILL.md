---
name: sdd
description: >-
  Spec-driven development for this repo. Use for every product change — features (planned
  changes, refactors, migrations) and investigations (bugs, incidents). Classifies the change,
  then sequences classify → spec → tests → implement → sync & archive against the Agentic
  Engineering Standards. Trigger when asked to add a feature, fix a bug, change behaviour, or
  "write a change spec".
---

# SDD — spec-driven development

Standard: Agentic Engineering Standards v1.2.0. This file is the orchestrator: it classifies the
change and sequences the phases below. [`executor.md`](executor.md) does each phase's work;
[`reviewer.md`](reviewer.md) independently gates it. Scale ceremony to the change — a one-line
behavior tweak needs a short spec and a couple of tests, a new subsystem needs the full
treatment — but the phase order and every gate below are non-negotiable.

## Setup

Before Phase 1, create `.agent-work/<slug>/checklist.md` listing every gate below plus the full
text of each checklist that will apply. Check items off as you go. A checked box is a claim to
verify, not proof by itself — see [`reviewer.md`](reviewer.md) for how it gets verified.

## Phases

1. **Classify** — `feature` or `investigation`; branch named to match. Gate: recorded.
2. **Spec PR** — reviewed and merged before any implementation starts. Gate:
   [`checklists/spec-quality.md`](checklists/spec-quality.md) passes and the PR is merged.
3. **Tests** — projected from the merged spec, before implementation. Gate: every acceptance
   criterion and enumerated edge case maps to a test; changed tests fail for the right reason
   first.
4. **Implementation PR(s)** — one or more, each within the approved spec. Gate: every gate in
   `AGENTS.md` exits zero; [`checklists/code-quality.md`](checklists/code-quality.md) and
   [`checklists/implementation-completeness.md`](checklists/implementation-completeness.md)
   (this PR's slice) pass.
5. **Archive + Living Docs PR** — after every Implementation PR has merged; introduces no new
   product behavior. Gate: every `Doc Sync` entry applied verbatim;
   [`checklists/implementation-completeness.md`](checklists/implementation-completeness.md)
   passes against the complete merged change; the spec is moved to
   `specs/changes/archive/YYYY-MM-DD-<slug>/`.

Phase detail: [`executor.md`](executor.md). Review protocol: [`reviewer.md`](reviewer.md).
Change-spec template: [`templates/change-spec.md`](templates/change-spec.md).
`superpowers:brainstorming`, `superpowers:writing-plans`, and
`superpowers:test-driven-development` are available when a design or test set is non-obvious.
