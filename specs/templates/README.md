# Living-doc templates

> Standard: Agentic Engineering Standards v1.2.0

Strict templates for the per-component living docs, tailored to plant-journal. They govern both the app's docs (`specs/{design,contracts,testing,operational}.md`) and the pipeline's (`ci/specs/*.md`) — hence the `<Component>` titles.

Rules that apply to every living doc:

- **One fact, one place.** Link to the canonical home instead of restating it: API schema and endpoints → [`contract/openapi.yaml`](../../contract/openapi.yaml); domain terms → [`GLOSSARY.md`](../../GLOSSARY.md); commands and repo map → [`README.md`](../../README.md); test conventions → [`CONTRIBUTING.md`](../../CONTRIBUTING.md); pipeline / versioning / release → [`ci/specs/`](../../ci/specs).
- **Omit, don't pad.** A section with nothing non-duplicated to say is deleted, not left empty.
- **Architecture, not a second implementation.** Design documents use diagrams and brief annotations for domain relationships, use cases, and workflows; contracts documents enumerate each contract surface, linking to OpenAPI rather than restating HTTP operations. Testing documents explain strategy, not test or fixture inventories.
- **Single voice, current state.** Describe how it works today; no decision archaeology or hedging.

The SDD skill's Sync & Archive phase updates the living docs against these templates.
