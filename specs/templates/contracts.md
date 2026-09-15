# <Component> contracts

> Standard: Agentic Engineering Standards v1.2.0
>
> Living-doc template. The API schema has one home — the generated contract/openapi.yaml. Never restate field-level request/response shapes here; link to it. Domain meaning → design.md; error-handling philosophy and conventions → CONTRIBUTING.md.

## HTTP API

<!-- The endpoints this component exposes, one line each, pointing to contract/openapi.yaml (which is
single-sourced from the tapir endpoints) for the schema. Do not paste fields here. -->

## Error responses

<!-- Every error the API returns or propagates: its meaning and the client behaviour expected on it.
Behaviour, not the code that raises it. Omit if the surface has none beyond standard success. -->

## Versioning & compatibility

<!-- Breaking vs non-breaking expectations for the interface. Note that contract drift is enforced in
CI (`dagger call contract-drift`), so the committed contract and the served API cannot diverge. -->
