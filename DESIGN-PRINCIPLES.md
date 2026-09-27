# Design principles

How this codebase is designed and built. These are engineering principles, not agent instructions — they apply to anyone working here, human or otherwise. They are adapted from the UPS RO Application Design Guidelines (Ports & Adapters, DDD, Fractal Design, Anti-Corruption Layers, Indirection Layers, Strict Build Guardrails), which are language-agnostic; this document grounds them in the choices this repo actually makes.

The aims:

- Low, even cognitive load across the codebase.
- Cheap to extend, and cheap to change on purpose.
- Maintenance and dependencies that stay healthy over the long run.
- Reuse and modularity, favoured over one-off solutions.

What we don't chase: gold-plating — polish past the point where it benefits the business or the team.

## 1. Ports & Adapters

The core is isolated from the outside world. Pure types live in `domain`; the orchestration services that use them live in `usecases`. A business-external dependency is named by a **port** — a `trait` in `ports/`, expressed in domain terms, not a vendor's — while generic infrastructure any service could need regardless of business logic (time, identity, logging, mutual exclusion) is a **capability** in `capabilities/`; whether something happens to have a swappable adapter isn't the test. Concrete **adapters** implement ports and capabilities and live apart, under `adapters/` (one subpackage per external dependency, e.g. `http`, `sqlite`). The composition root in `app/` is the only place that binds an adapter to a port or capability, injecting it with `using`.

Because the core depends only on ports and capabilities, it is exhaustively unit-tested by substituting them. Adapters are proven at their seam — the SQLite adapter against a real in-memory database, the HTTP adapter against the served contract — never by mocking the thing they exist to talk to.

## 2. Domain-Driven Design (a pragmatic subset)

The domain is modelled explicitly and made hard to misuse:

- **Strong types over primitives.** Identifiers, quantities, and labels are their own types (`PlantId`, `OperationId`, a percentage that is known to be in range), not bare `String`/`Int`.
- **Invalid states are unrepresentable.** Prefer a type that cannot hold a bad value over a check that might be forgotten; prefer a total `enum` and exhaustive matches over open-ended strings. Avoid nullable/optional fields where a value is always present.
- **Domain values hold no capabilities.** Values carry data and rules only — no clock, database, or I/O. Domain services orchestrate capability ports around those pure values.
- **Give event time an explicit owner.** When an event can be recorded after it happened, its timestamp is caller input, not an implicit server clock read. Convert local entry to an absolute instant at the boundary, validate it, and preserve it as part of the event's history.
- **Ubiquitous language.** Code, docs, and the UI use one vocabulary, in English, matching how we talk about plants and care. Romanian source records are translated at the boundary (see §4), never carried inward.

## 3. Fractal Design

The system reads the same at every zoom level: `main` is the most zoomed-out view, and you can descend — composition root → service → domain → method — with each level staying small and single- purpose. Concretely:

- **One responsibility per unit**, one reason to change.
- **One abstraction level per method** — don't mix high-level orchestration with low-level detail in the same body. Methods stay short (roughly 7–10 lines). Keep control flow linear and information-dense: name derived values once, make conditions explicit, and avoid expressions whose formatting contributes more lines than meaning.
- **Most-important-first ordering, everywhere.** Public entrypoints come before private helpers; the critical parameters of a function before the incidental ones; the defining fields of a case class before the rest. Someone opening a file should meet its purpose first, not its plumbing. A file that greets the reader with private helpers is ordered backwards.
- **Locality of Behavior over structural deduplication.** Keep behavior beside the state and UI that it governs. Similar-looking code is not sufficient reason to share an abstraction when the use cases may evolve independently.
- **DRY applies to knowledge, not lines.** Centralize a rule, decision, or contract when independent divergence would be a bug. Allow incidental code similarity when the duplicated code represents separate knowledge or responsibilities.
- **Every abstraction must own a real seam.** Extract an interface, component, or helper only when it names a stable responsibility and has real consumers. Do not replace local code with configuration bags, pass-through layers, or generic frameworks that merely hide differences.
- **Represent one fact once.** When one measurement determines a classification, model the valid classifications as one closed type carrying the measurements that apply to each case. Do not expose parallel fields that callers can combine into contradictions.
- **Keep mutable records separate from cached projections.** A projection carries identity and derived measurements; read current entity details from their authoritative source and join by identity. A failed read or mismatched identity must not appear as a successful, current view.
- **Layout follows content constraints, not one viewport.** UI components own their structural boxes, use fluid bounds, and define narrow-width behavior. Decorative layers must not stand in for structure that controls layout.

## 4. Anti-Corruption Layer

External models are translated into internal ones at the edge, so no foreign shape or semantics leak into the domain. Two data worlds are kept distinct: **wire DTOs** (the tapir request/response types, single-sourced into the OpenAPI contract) and **domain models**. Adapters translate between them, and validators reject malformed input before it crosses inward. Boundary encodings must preserve domain semantics used outside the core: if storage compares encoded values, their canonical representation must preserve domain ordering, including across migrated data. The import path that ingests the Romanian source records is the largest instance of this: it maps a foreign vocabulary into the English domain.

## 5. Indirection Layers

Just as the ACL protects the domain from the outside, interfaces protect internal components from each other. Business collaborators talk through explicit `trait`s; the only things called concretely are pure, stateless utilities. Two rules follow:

- **Caller-defined interfaces.** A service interface is shaped by what its consumer needs, kept minimal and named in the consumer's terms — not dictated by an implementation.
- **Adapters depend on services, never on other adapters or on a capability directly.** An HTTP handler calls an application service and, in its own tests, substitutes that service. It must not reach past the service to a port such as the database — the database is reached only through its port, and only from within the service that owns that interaction.

## 6. Strict Build Guardrails

The build is deliberately strict, and staying inside the lines is what makes high speed safe (learn the Formula 1 car; then you go fast on the track). The guardrails:

- **100% coverage — as a means, not a goal.** Full coverage is a signal, not a trophy: when something is hard to test, that is the design telling you to fix it, not a reason to hack the test. The HTTP layer is tested at its seam — logic-bearing endpoints through the tapir stub interpreter, static serving against a live server — not excluded. The one narrow exclusion is the composition root in `app/`, which only wires already-tested parts together and is exercised by the packaged runtime. Never lower a threshold, disable an inspection, or widen an exclusion to get to green.
- **Static analysis with zero tolerance.** scalafix `DisableSyntax` and WartRemover fail the build on any finding; ESLint, Prettier, and dependency-cruiser do the same on the frontend. Start at zero warnings and stay there.
- **A strict compiler.** `-Werror`, `-Wunused:all`, `-Wvalue-discard`, and a pinned Java output version turn whole classes of mistake into compile errors.
- **Healthy dependencies.** The toolchain and libraries are pinned (mise, `build.sbt`, `package.json`) and kept current, so CVEs and deprecations are paid down continuously rather than in a late, expensive migration.
- **Dependency direction is mechanised, not reviewed — mostly.** `ArchitectureComponentTest` (ArchUnit) fails the build if `domain` or `capabilities` gains an outgoing dependency, `ports` depends on anything but `domain`, or `usecases` reaches into `adapters`/`app` — those hold by construction, not by review discipline. Adapters are checked only for a dependency cycle on each other; a one-way adapter dependency still passes and needs a human to catch it.

### Conventions the guardrails don't (or can't) mechanise

- **Code documents itself; comments are the exception.** Names carry the meaning. A comment that restates what the code does is duplicated knowledge that drifts out of step with it — and DRY is about knowledge, not lines. So: no scaladoc/JSDoc narrating a type, method, or field; no notes about code that used to be here or once didn't compile (git holds the history). A comment earns its place only when it records _why_ a non-obvious choice was made and that reason cannot live in a name — a build workaround for a library bug, a deliberate deviation. Contract-level text that ships to consumers (an endpoint `summary`, an OpenAPI description) is API content, not a code comment, and stays.
- **Real imports, not fully-qualified paths.** Reach for a symbol by importing it; inline `a.b.c.Thing` references are noise that hides dependencies.
- **Don't contort a test to cover glue.** If covering a line needs a cast to `Any`, an untyped response, or similar gymnastics, that is the "hard to test ⇒ fix the design" signal: either the code wants a seam, or it is a transport shell that belongs in the coverage exclusion — not a hack.
- **`Wart.Any` is intentionally _off_.** tapir encodes "no streaming capability" as the type parameter `Any` in every endpoint signature, so a blanket ban fires on all adapter code and cannot be used here. The `Any`-widening hacks it would otherwise catch are prevented by the two rules above and by scalafix's ban on the `asInstanceOf`/`isInstanceOf` family.

## Direct style, no effect system

The backend is direct-style on Java 25 + Loom. Capabilities are ordinary values injected with `using`; there is no `IO`/`Task` monad and no Cats Effect or ZIO. Blocking calls are fine — a virtual thread carries them — so code reads top-to-bottom without a runtime wrapper.
