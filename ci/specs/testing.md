# Pipeline testing

> Standard: Agentic Engineering Standards v1.2.0

## Strategy

The pure decision logic carries the risk, so it carries the tests. `selection.ts` and `version.ts`
(and `domain.ts` through them) are unit-tested with Vitest to 100% statements, branches, functions,
and lines. The side-effecting hooks (`hooks/**`), the entrypoint (`index.ts`), and the constants
(`buildEnv.ts`) are excluded from coverage and validated by running the pipeline itself.

## Traceability

- Path classification and the Selection algebra (design.md → Domain model, Processing rules) are
  covered by `test/selection.componentTest.ts`.
- Version derivation (design.md → Processing rules; contracts.md → Version) is covered by
  `test/version.componentTest.ts`.

## Pipeline validation

End-to-end behaviour is validated by running the pipeline, not by unit tests: `dagger call verify
--all` (all component checks green) and `dagger call build-image` (produces a runnable runtime
image). These exercise the real containers.
