# <Component> testing

> Standard: Agentic Engineering Standards v1.2.0
>
> Living-doc template. Cross-cutting conventions — test categories, file naming, mocking strategy,
> coverage gates — live in CONTRIBUTING.md; do NOT repeat them here. This file holds only what is
> specific to testing THIS component.

## Service-specific strategy

<!-- What is unique about testing this component and why, tracing back to the design.md behaviours
under test. If nothing is unique yet, omit the file entirely. -->

## Fixtures & data setup

<!-- Component-specific fixtures and data (e.g. in-memory SQLite for persistence seam tests; the
Romanian→English import fixtures once that feature lands), enough that a new test needs no
reverse-engineering. -->

## Integration boundaries

<!-- Which dependencies are real vs stubbed in each test category, and why (e.g. real SQLite in seam
tests; capability ports stubbed in component tests). -->
