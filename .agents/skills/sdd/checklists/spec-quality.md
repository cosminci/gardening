# Spec quality checklist

Gate for the Spec PR. Fill from the spec and [`templates/change-spec.md`](../templates/change-spec.md) — never from memory of a past spec.

- [ ] Classification (`feature`/`investigation`) is recorded and the branch name matches it.
- [ ] Every sentence lives in exactly one section; removing any sentence loses information — no fact is restated across sections.
- [ ] `What & Why`, `Acceptance Criteria`, and `Doc Sync` are present and non-empty; every other section is present only because it carries real content, not filler.
- [ ] Stays at behavior/contract altitude throughout: no file paths, class names, adapter or library choices, module layout, or diff walkthroughs anywhere in the spec.
- [ ] `Domain / Design Notes` is present whenever domain contracts, ports, or service signatures change, and absent otherwise.
- [ ] If Classify called for a spike, `Domain / Design Notes` and `Alternatives Considered` reflect its findings — no proposal the spike found untractable, no domain type or port reasoned from scratch instead of from what was actually tried.
- [ ] Each `Acceptance Criteria` item is externally observable/testable and covers failure, boundary, and accessibility behavior where relevant; none merely restates `What & Why`.
- [ ] Each `Doc Sync` entry names a doc, a section, and the exact new/revised fact — not a topic name or this checklist's own wording.
- [ ] `Invariants` holds only pre-existing guarantees, never this change's own rules. `Tradeoffs Accepted` holds only a real downside with who bears it. `Out of Scope` holds only what a reader would reasonably wonder about, not a restatement of the criteria's own exclusions. Any of the three is omitted rather than left empty.
- [ ] Proportional to the closest approved precedent spec: if this change is smaller than that precedent but the proposal is the same size or larger, cut it.
