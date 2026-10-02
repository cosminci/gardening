# Design principles

How this codebase is designed and built. These are engineering principles, not agent instructions — they apply to anyone working here, human or otherwise.

These principles are language-agnostic; this document grounds them in the choices this repo actually makes.

The aims:

- Low, even cognitive load across the codebase.
- Cheap to extend, and cheap to change on purpose.
- Intentional changes that respect the domain and its code structure.
- Maintenance and dependencies that stay healthy over the long run.
- Reuse and modularity, favoured over one-off solutions.

What we don't chase: gold-plating — polish past the point where it benefits the business or the team.

## 1. Ports & Adapters

The core is isolated from the outside world. Pure types live in `domain`; the orchestration services that use them live in `usecases`. A business-external dependency is named by a **port** — a `trait` in `ports/`, expressed in domain terms, not a vendor's — while generic infrastructure any service could need regardless of business logic (time, identity, logging, mutual exclusion) is a **capability** in `capabilities/`; whether something happens to have a swappable adapter isn't the test. Concrete **adapters** implement ports and capabilities and live apart, under `adapters/` (one subpackage per external dependency, e.g. `http`, `sqlite`). The composition root in `app/` is the only place that binds an adapter to a port or capability, injecting it with `using`.

Static configuration flows the same way. The composition root loads and parses it, converting each setting into the domain type it feeds — `domain` and `usecases` do no config parsing or conversion of their own. A static value never varies between calls, so it is captured once at construction (a `make`/constructor parameter), never threaded through a port's method or any per-call API.

Because the core depends only on ports and capabilities, it is exhaustively unit-tested by substituting them. Adapters are proven at their seam — the SQLite adapter against a real in-memory database, the HTTP adapter against the served contract — never by mocking the thing they exist to talk to.

## 2. Domain-Driven Design (a pragmatic subset)

The domain is modelled explicitly and made hard to misuse:

- **Strong types over primitives.** Identifiers, quantities, and labels are their own types (`PlantId`, `OperationId`, a percentage that is known to be in range), not bare `String`/`Int`.
- **Invalid states are unrepresentable.** Prefer a type that cannot hold a bad value over a check that might be forgotten; prefer a total `enum` and exhaustive matches over open-ended strings. Avoid nullable/optional fields where a value is always present.
- **Domain values hold no capabilities.** Values carry self-contained, independently testable data and rules only — no clock, database, or I/O. Domain services orchestrate capability ports around those pure values.
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

External models are translated into internal ones at the edge, so no foreign shape or semantics leak into the domain. This boundary absorbs external model changes without forcing changes to business logic.

Two data worlds are kept distinct: **wire DTOs** (the tapir request/response types, single-sourced into the OpenAPI contract) and **domain models**. Adapters translate between them, and validators reject malformed input before it crosses inward.

Boundary encodings must preserve domain semantics used outside the core: if storage compares encoded values, their canonical representation must preserve domain ordering, including across migrated data. The import path that ingests the Romanian source records is the largest instance of this: it maps a foreign vocabulary into the English domain.

## 5. Indirection Layers

Just as the ACL protects the domain from the outside, interfaces protect internal components from each other. Business collaborators talk through explicit `trait`s; the only things called concretely are pure, stateless utilities.

- **Caller-defined interfaces.** A service interface is shaped by what its consumer needs, kept minimal and named in the consumer's terms — not dictated by an implementation.
- **Contracts expose responsibilities, not implementations.** Injected interfaces make dependencies understandable and substitutable in tests without reopening or redundantly testing collaborator internals. Defining those contracts forces deliberate abstraction and domain modelling.
- **Adapters depend on services, never on other adapters or on a capability directly.** An HTTP handler calls an application service and, in its own tests, substitutes that service. It must not reach past the service to a port such as the database — the database is reached only through its port, and only from within the service that owns that interaction.

## 6. Strict Build Guardrails

The build is deliberately strict, and staying inside the lines is what makes high speed safe (learn the Formula 1 car; then you go fast on the track). Every guardrail is a means to modularity, quality, maintainability, and security, not a goal in itself.

- **100% coverage — as a means, not a goal.** Full coverage is a signal, not a trophy: when something is hard to test, that is the design telling you to fix it, not a reason to hack the test. The HTTP layer is tested at its seam — logic-bearing endpoints through the tapir stub interpreter, static serving against a live server — not excluded. The one narrow exclusion is the composition root in `app/`, which only wires already-tested parts together and is exercised by the packaged runtime. Never lower a threshold, disable an inspection, or widen an exclusion to get to green.
- **Static analysis with zero tolerance.** scalafix `DisableSyntax` and WartRemover fail the build on any finding; ESLint, Prettier, and dependency-cruiser do the same on the frontend. Start at zero warnings and stay there.
- **A strict compiler.** `-Werror`, `-Wunused:all`, `-Wvalue-discard`, and a pinned Java output version turn whole classes of mistake into compile errors. Choose compiler and analysis rules that reject unsafe constructs, unused imports, and implicit numeric widening.
- **Healthy dependencies.** The toolchain and libraries are pinned (mise, `build.sbt`, `package.json`); automate update proposals to keep them current and pay down CVEs, deprecations, and end-of-life risks continuously rather than in a late, expensive migration.
- **Dependency direction is mechanised, not reviewed.** `ArchitectureTest` (ArchUnit) fails the build if `domain` or `capabilities` gains an outgoing dependency, `ports` depends on anything but `domain`, `usecases` reaches into `adapters`/`app`, or an adapter depends on another adapter — the five-package boundary in §1 holds by construction, not by review discipline.

### Conventions the guardrails don't (or can't) mechanise

- **Real imports, not fully-qualified paths.** Reach for a symbol by importing it; inline `a.b.c.Thing` references are noise that hides dependencies.
- **Don't contort a test to cover glue.** If covering a line needs a cast to `Any`, an untyped response, or similar gymnastics, that is the "hard to test ⇒ fix the design" signal: either the code wants a seam, or it is a transport shell that belongs in the coverage exclusion — not a hack.
- **`Wart.Any` is intentionally _off_.** tapir encodes "no streaming capability" as the type parameter `Any` in every endpoint signature, so a blanket ban fires on all adapter code and cannot be used here. The `Any`-widening hacks it would otherwise catch are prevented by the two rules above and by scalafix's ban on the `asInstanceOf`/`isInstanceOf` family.

## 7. Keeping Complexity in Check

§3 shapes the system at rest; this is how it holds that shape as it grows. Complexity must earn its place through the value it delivers; narrow or reject a change when that tradeoff does not hold.

- **Occam's Razor.** Compare alternatives that fit the system; prefer fewer assumptions and moving parts.
- **Scope versus complexity.** Ask whether 90% of the value is achievable with 10% of the complexity.
- **Intentional change.** Before the spec, decide what to extend, reuse, build, or split; record why, and revisit the decision as evidence changes.
- **Refactor as work arrives.** Extract newly exposed responsibilities into their own modules in the change that exposes them, not a later cleanup. Leave no broken windows behind.
- **Size is measured, not felt.** A unit past a screenful, a test suite past roughly 3× the code it exercises, or code that is difficult to test warrants inspection, not an automatic split. Split distinct responsibilities and justify larger cohesive units; never trim the suite to hide the ratio.
- **Complexity outranks completeness.** When holding complexity down conflicts with shipping more, reduce complexity first and ship the fuller feature second.

## 8. Code Expresses Intent

Meaningful names, strong types (§2), clear constructs, and cohesive modules (§3) carry intent. Comments are often design smells: investigate the use case that needs explaining before adding one.

- **Design before commentary.** Question the API, domain model, and responsibility boundaries; rewrite the code to remove the need for explanation when possible. A newly identified subdomain may need its own package.
- **High-signal documentation.** Keep rationale in concise, authoritative docs. Retain comments only for constraints or non-obvious reasons code cannot express, such as a library workaround or deliberate deviation.
- **Avoid duplicated narration.** No scaladoc/JSDoc restating a type, method, or field; no notes about code that used to be here or once didn't compile (git holds the history). Contract-level text that ships to consumers (an endpoint `summary`, an OpenAPI description) is API content, not a code comment, and stays.

## Direct style, no effect system

The backend is direct-style on Java 25 + Loom. Capabilities are ordinary values injected with `using`; there is no `IO`/`Task` monad and no Cats Effect or ZIO. Blocking calls are fine — a virtual thread carries them — so code reads top-to-bottom without a runtime wrapper.
