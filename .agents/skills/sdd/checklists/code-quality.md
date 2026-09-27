# Code quality checklist

Gate for every Implementation PR. First verify against `CONTRIBUTING.md` §Naming, §Scala
composition, §Logging, §Metrics, §Testing conventions, and `DESIGN-PRINCIPLES.md` §2/§3/§6 —
those own the general rules; do not restate them here. Then check the items below, which live
only in this checklist.

- [ ] Assertions are one physical line in the normal case; extract named `expectedX`/`actualX`
  values (decomposing nested expressions into values as needed) to make that possible.
- [ ] Private members omit their return type unless it is `Unit`.
- [ ] Each affected suite was reviewed as a whole against current supported behavior — edited,
  merged, or removed as use cases evolved, not just appended to for coverage.
- [ ] Suite order within a file: reusable non-trivial mock data, then use-case tests, then
  observable `Refs` (only if needed), then `buildX` helpers.
- [ ] At most one `Refs` instance per test; a test wanting more than one is really two use cases
  — split it.
- [ ] `Refs` holds only observed collaborator effects/calls a test asserts, never a stored mock
  response; `buildX` constructs every collaborator substitute and takes fixed
  responses/failures/clocks as parameters with ordinary-case defaults.
- [ ] A stub/mock is declared in the one suite that needs it, unless it is non-trivial (roughly
  4+ lines to define) *and* shared by more than one suite — only then does it move to
  `TestImplicits` (a capability `given`) or a shared `Mocks` object (domain data).
- [ ] Prefer a codec that encodes the wire format directly over a DTO; a DTO exists only when it
  cannot leak past its boundary and no codec can express the format cleanly.
- [ ] A test needs no helper beyond its `buildX` call to read as one use case.
- [ ] Fixtures are declared in semantic scenario order, grouped by the state or use case they
  describe, not by type.
- [ ] Test names interpolate the actual domain value under test (e.g.
  `s"should return ${WateringAttention.Current} ..."`) rather than spelling it out, so a rename
  can't silently make the name wrong.
- [ ] Application logic sits behind a trait named for its behavior, with a same-named companion
  exposing `make` and a private `LiveX`; tests exercise only what `make` returns.
- [ ] Integration tests acquire/use/release their own resource explicitly through the
  ecosystem's standard primitive — never through a callback-style setup helper.
- [ ] Tests are Arrange / Act / Assert, each block separated by a blank line.
- [ ] Values are named for the meaning they establish, not the helper call that produced them
  (`wateredPlantOperations`, not `watered`).
