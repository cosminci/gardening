# <Component> design

> Standard: Agentic Engineering Standards v1.2.0
>
> Living-doc template. Fill a section only when it carries current, non-duplicated fact; delete each
> guidance comment as you populate it, and omit a section entirely rather than leave it empty.
>
> Belongs elsewhere, not here: build/run commands and repo map → README.md; term definitions →
> GLOSSARY.md; API and schemas → contracts.md + contract/openapi.yaml; test strategy → testing.md;
> operating the running app → operational.md; pipeline/versioning/release → ci/specs/.

## Service overview

<!-- 2–4 sentences: what this component does and its role in plant-journal. No commands, no API
schemas, no test/ops detail. A reader grasps the purpose before reading further. -->

## Domain model

<!-- The concepts this component works with — use the GLOSSARY terms, do not redefine them — with
their relationships and governing rules. Meaning and model, not the SQLite schema or JSON shapes.
Add one mermaid/ER diagram only if it clarifies relationships. -->

## Processing rules

<!-- For every input/operation the component accepts, what happens, as observable behaviour. A
reader can predict the output for any input from this alone. No class/file/method names. -->

## Edge cases

<!-- Explicit list of error conditions, boundary values, and degraded-mode behaviour, each with its
outcome. Never "handles edge cases gracefully". -->

## Invariants

<!-- Properties that must always hold, as pass/fail statements (e.g. English-only; plants ordered by
location; a field's values come only from its option list). Pre-existing guarantees, not a feature's
own new rules. No "prefer / minimize". Omit if none. -->

## Component architecture

<!-- The hexagonal layers (domain / capabilities-ports / adapters / app) and how capabilities are
injected (`using`). At least one mermaid diagram. No code walkthrough; no dependency list the build
file already states. -->

## Tech debt

<!-- Known compromises only. Each item: what's wrong, why it was accepted, what triggers a fix. Omit
the section if there is none. -->
