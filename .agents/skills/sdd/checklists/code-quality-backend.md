# Code quality checklist — backend

Load only for a PR that changes `backend/**`. `CONTRIBUTING.md` §Naming, §Scala composition, §Logging, §Metrics, and `DESIGN-PRINCIPLES.md` §1/§4/§5 own the general rules, and the shared items live in [`code-quality.md`](code-quality.md); the items below live only here.

- [ ] Private members omit their return type unless it is `Unit`.
- [ ] Suite order within a file: reusable non-trivial mock data, then use-case tests, then observable `Refs` (only if needed), then `buildX` helpers.
- [ ] At most one `Refs` instance per test; a test wanting more than one is really two use cases — split it.
- [ ] `Refs` holds only observed collaborator effects/calls a test asserts, never a stored mock response; `buildX` constructs every collaborator substitute and takes fixed responses/failures/clocks as parameters with ordinary-case defaults.
- [ ] A stub/mock is declared in the one suite that needs it, unless it is non-trivial (roughly 4+ lines to define) _and_ shared by more than one suite — only then does it move to `TestImplicits` (a capability `given`) or a shared `Mocks` object (domain data).
- [ ] Prefer a codec that encodes the wire format directly over a DTO; a DTO exists only when it cannot leak past its boundary and no codec can express the format cleanly.
- [ ] A test needs no helper beyond its `buildX` call to read as one use case.
- [ ] Application logic sits behind a trait named for its behavior, with a same-named companion exposing `make` and a private `LiveX`; tests exercise only what `make` returns.
- [ ] Integration tests acquire/use/release their own resource explicitly through the ecosystem's standard primitive — never through a callback-style setup helper.
