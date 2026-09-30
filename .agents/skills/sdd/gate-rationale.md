# SDD quality-gate evals

Behavioral scenarios that justify the quality gates in this skill. Each was run RED (against the skill _before_ the gate existed) to prove the gap is real, then GREEN (after) to prove the gate closes it. Run a subject on a representative-or-weaker model (not the strongest available), context-free — the point is that the _instruction_ does the work, not raw model capability.

Not a harness. When a gate is added or reworded, add or re-run the matching scenario here; grow a real runner only once these outnumber what one person can run by hand (DESIGN-PRINCIPLES §7).

## 1. Tests are a projection, not an accretion

Guards: `checklists/code-quality.md`, projection item.

Scenario: an existing backend codec with a passing round-trip suite gains one new optional wire field the current tests don't populate. Ask, following the code-quality checklist, how the suite should change.

- **RED** (weak "reviewed as a whole… not just appended" wording): agent quoted the line as justification yet planned "add one new test" for the field — the exact add-a-test reflex the line was meant to prevent.
- **GREEN**: agent extends/retargets the existing use-case tests to carry the new field; a net-new test appears only for genuinely new observable behavior with no owning test.

## 2. Complexity — size is measured, not felt

Guards: `DESIGN-PRINCIPLES.md` §7; `checklists/code-quality.md` size item.

Scenario: a single-responsibility component (~280 lines, still one coherent thing) gains three more fields (~50 lines) with an already-~700-line test file. Ask how to implement, addressing component and suite size.

- **RED** (only §3, purely qualitative): "the docs do not establish a size or line-count threshold — I simply add the fields," size never examined.
- **GREEN**: agent measures against §7's signal (screenful; suite past ~3× its code) and investigates a split or records why the growth is justified.

Note: §7's _split-the-visible-mess_ and _decline-to-build_ ideas were tested too and came back GREEN under existing §3 alone — so §7 owns only what §3 lacks (timing, measurement, priority), and the redundant "evolve, don't add" bullet was dropped rather than added.

## 3. Frontend PRs load frontend gates, not backend noise

Guards: `reviewer.md` / `executor.md` surface routing; `checklists/code-quality-frontend.md`.

Scenario: a frontend-only PR (a SolidJS component + its Vitest test). As reviewer, determine which checklists load and apply the code-quality gate.

- **RED** (single backend-flavored `code-quality.md`): reviewer loaded it and found ~60% of items were Scala constructs (`Refs`/`buildX`, traits+companions, codecs) to mark not-applicable; "no frontend-specific gates exist."
- **GREEN**: reviewer loads shared `code-quality.md` + `code-quality-frontend.md` only, never the backend leaf, and has frontend gates (pure-logic extraction, disabled-control reason, a11y-query contract, contract-mirroring, justified `v8 ignore`) to check.
