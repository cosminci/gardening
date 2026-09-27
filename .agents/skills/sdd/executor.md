# SDD executor

Does the work for one phase at a time, in order, as sequenced by [`SKILL.md`](SKILL.md). Each phase below names its checklist or template — read that file; do not restate it here.

## Classify

Decide `feature` (planned change, refactor, migration — you already know the new behavior) or `investigation` (bug/incident — the spec is a trail toward a root cause). Name the branch `feature/<slug>` or `investigation/<slug>` and record the classification in `.agent-work/<slug>/checklist.md`.

Then decide whether a spec is required: draft the Doc Sync entry first — one line naming a doc, section, and specific fact. A non-empty entry means write the spec; a pure refactor or internal cleanup with nothing to put there doesn't need one. Only the maintainer may waive this requirement, explicitly and for a specific change — record the decision (or waiver) in `.agent-work/<slug>/checklist.md`.

Also decide whether the problem and its solution are already clear enough to spec directly — you know exactly what to solve, how, and its acceptance criteria — or whether the space needs a spike first: an unclear problem, an algorithmic or efficiency-sensitive design, or any proposal you can't yet state in concrete domain types and ports. When unsure, spike — a spec written from a guess about the problem space routinely turns out largely untractable once implementation starts. Record the decision in `.agent-work/<slug>/checklist.md`.

## Spike

Only when Classify decided the space isn't clear yet; otherwise skip straight to Spec PR. Actually try to solve it — prototype the hard part, write throwaway code against the real APIs, work through the algorithm — because thinking alone under-informs a plan the way a floorplan drawn before walking the site does. Stop once you can state, concretely, the domain types, the ports, the real edge cases, and whether the approach is tractable at all.

This code is disposable: it never needs to pass `AGENTS.md`'s gates, is never the Implementation PR's diff, and is not reviewed. Record what you learned in `.agent-work/<slug>/` — it becomes the Spec PR's `Domain / Design Notes` and `Alternatives Considered`, grounded in what you actually found rather than renewed first-principles guessing.

## Spec PR

Read [`templates/change-spec.md`](templates/change-spec.md) fresh — never recreate it from memory. Read the closest approved precedent spec (the archived capability-boundaries-and-doc-policy spec, for domain/port changes) for altitude and density; match its concision, not its exact headings. When a spike preceded this phase, ground `Domain / Design Notes` and `Alternatives Considered` in its findings.

- **Feature:** current → new behavior, acceptance criteria, invariants, tradeoffs, Doc Sync.
- **Investigation:** hypotheses and evidence for/against each, narrowing to a proven root cause — the spec's `What & Why` ends up stating that root cause and the hypotheses it ruled out.

Self-check against [`checklists/spec-quality.md`](checklists/spec-quality.md), then open the Spec PR — the proposal and planning artifacts only, no production code. Do not start implementation until it is reviewed and merged.

## Tests

Derive tests from the merged spec's acceptance criteria and enumerated edge cases, before writing implementation code. A test must trace to a spec statement and fail only when that stated behavior changes — never reverse-engineered from code already written. Follow `CONTRIBUTING.md`'s testing conventions for suite naming and tiering.

## Implementation PR(s)

Implement the approved behavior. Split into multiple PRs on separable seams, or when one PR would exceed a focused review session — never to dodge a gate. Each PR links the merged spec.

Make the tests pass, then run the gates `AGENTS.md` already defines (backend/frontend/pipeline commands, `dagger call verify`, contract drift if you touched tapir endpoints) — do not repeat those commands here, and never weaken a gate to pass one. Self-check against [`checklists/code-quality.md`](checklists/code-quality.md) and [`checklists/implementation-completeness.md`](checklists/implementation-completeness.md) (against this PR's own slice) before requesting review.

## Archive PR

After every Implementation PR has merged: apply each `Doc Sync` entry verbatim to the named living doc and section (`specs/*.md`, or `ci/specs/*.md` for pipeline changes) — prune obsolete or duplicated content rather than appending. Move the spec to `specs/changes/archive/YYYY-MM-DD-<slug>/`.

Before opening this PR, self-check [`checklists/implementation-completeness.md`](checklists/implementation-completeness.md) against the **complete merged change**, not just the last PR — this is the hard stop that catches a spec archived next to an incomplete implementation. This PR introduces no new product behavior.
