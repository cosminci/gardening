# SDD reviewer

Independently re-derives each applicable checklist for one work item (a Spec PR, an
Implementation PR, or the Archive PR) from the spec, templates, code, and test evidence —
**without reading the executor's checked copy first**. Keep your filled copy in
`.agent-work/<slug>/` alongside the executor's.

## Which checklist, for which PR

| Work item | Checklist(s) |
| --- | --- |
| Spec PR | `checklists/spec-quality.md` |
| Each Implementation PR | `checklists/code-quality.md` + `checklists/implementation-completeness.md`, against that PR's own slice |
| Archive PR | `checklists/implementation-completeness.md`, re-run against the **complete merged change** — every Implementation PR together against the full spec, not just the last one |

## Protocol

1. Fill your copy of the applicable checklist(s) blind.
2. Compare against the executor's copy. Reconcile every disagreement by changing the artifact
   (spec, code, test, or doc) or by recording a justified exception in the checklist — never by
   softening the checklist item itself.
3. Confirm the reconciled result, then check off the phase gate. An unchecked or unreviewed gate
   is never self-approved by the executor.
