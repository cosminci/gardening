# Editable care catalogs

> Standard: [Agentic Engineering Standards](https://github.com/Adobe-AIFoundations/agentic-workflow-standards) v1.2.0.
> Lifetime: open from creation through implementation, archived in the [sync docs & archive step](../../agentic-workflows.md#a-structured-development-skills).

<!-- Boundary clarifications or risk callouts attach inline to the section they qualify (an invariant, a tradeoff, an acceptance criterion). No free-floating "Risks" or "Open Questions" sections. -->

**Date:** 2026-09-21

<!-- One sentence describing the change. -->

## What & Why

<!-- What names the behavior change as a diff: current behavior → new behavior. Why names what's wrong with current behavior or what capability is missing. Both halves stated explicitly. No class names, file paths, variables, endpoints, schema fields, or line references — behavior and intent only. -->

## Alternatives Considered

<!-- Option A, B, etc. Why each was considered, why rejected. Mandatory if there were real alternatives considered — this is the ADR core. The archaeologist a year from now needs this more than the reviewer today. Omit this section if there are no real alternatives to consider — don't artificially fill this in. -->

## Invariants

<!-- Pre-existing guarantees of the surrounding system that this change must not break — properties that would be on the books with or without this change (compatibility, latency, safety, ordering, idempotency). Litmus test: "would this still have to hold if this change didn't exist?" Yes → invariant. No → it's the new behavior, so it belongs in Acceptance Criteria, not here. The change's own rules are never invariants. Pass/fail language, no "prefer / minimize / be careful". Omit this section if none. -->

- <invariant>

## Tradeoffs Accepted

<!-- What becomes worse or more constrained, and why that's acceptable. Captured at decision time because it's the hardest property to reconstruct after the fact. -->

## Acceptance Criteria

<!-- Observable conditions proving the change works, invariants still hold, and rollback / fallback behavior remains valid. This is where the new behavior lives, including any rule the change introduces — not in Invariants. Each item externally observable or testable. -->

- <criterion>

## Doc Sync

<!-- Living-doc edits the archive phase must apply. Name each affected doc (design.md, contracts.md, testing.md, operational.md, etc.) and the section or property that changes. Omit only if the change touches no living doc. -->

- <doc> — <section or property that changes>

## Out of Scope

<!-- OPTIONAL. ≤2 bullets in functional/business language. Default is to omit; include only when there's genuine ambiguity at the service boundary. Three or more bullets is a kitchen-sink signal. Delete this section entirely if not needed. -->
