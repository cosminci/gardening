# plant-journal

A searchable journal of the household's house plants — ordered by where each one lives, each showing its most recent dated care actions. English-only; source records are Romanian and are translated on import. Runs on the home NAS, reachable over LAN and Tailscale only, with no application authentication.

## Status

Foundation scaffold: the backend serves a `GET /health` liveness probe (for the host/container healthcheck) and the built frontend on one port, and the frontend renders a static app shell. Product features land one change spec at a time through the SDD skill — the next action in this repo is to write a change spec for the first feature.

## Stack

- **Backend** — Scala 3 (direct style, no effect system) on Java 25 + Loom; tapir (sync/Netty) over Magnum + SQLite.
- **Frontend** — SolidJS + TypeScript, built with Vite.
- **Contract** — the HTTP API is single-sourced from the backend's tapir endpoints to OpenAPI, and the TypeScript client is generated from it. Generated files under `contract/` are committed and read-only.
- **CI** — a TypeScript Dagger module (`.dagger/`) with an affected-component selector.
- **Packaging** — one slim, non-root runtime image serving the API and the built frontend.

## Prerequisites

Install the pinned toolchain (Java 25, Scala 3.8, Node LTS) with [mise](https://mise.jdx.dev): `mise install`. Docker is required for the Dagger pipeline and the image build. Commands below assume mise is shell-activated; otherwise prefix them with `mise exec --`.

## Commands

| Task | Command |
| --- | --- |
| Backend gate | `cd backend && sbt compile "scalafixAll --check" scalafmtCheckAll coverage test coverageReport` |
| Run the backend | `cd backend && sbt run` |
| Frontend gate | `cd frontend && npm run verify` |
| Frontend dev server | `cd frontend && npm run dev` |
| Pipeline gate | `cd .dagger && npm run verify` |
| Regenerate the contract | `cd backend && sbt "runMain gardening.app.GenerateOpenApi ../contract/openapi.yaml" && cd ../contract && npm run generate` |
| Affected checks (local / pre-push) | `dagger call verify` |
| All checks | `dagger call verify --all` |
| Build the runtime image | `dagger call build-image` |

## Repository map

| Path | What |
| --- | --- |
| `backend/` | Scala 3 service — `domain`, `capabilities` (ports), `adapters`, `app`. |
| `frontend/` | SolidJS single-page app. |
| `contract/` | Generated OpenAPI document and TypeScript client (read-only). |
| `.dagger/` | TypeScript Dagger CI module. |
| `specs/` | Living design / contracts / testing / operational docs, and change specs. |
| `ci/specs/` | The pipeline's own design / contracts / testing / operational docs. |
| `.claude/skills/sdd/` | The spec-driven development skill. |
| `plants/`, `guides/`, `shopping-list.md`, `GARDEN-GUIDE.md` | The Bucharest balcony-garden guide — import source for a later feature, not part of the app. |

## Documentation

The context files every contributor — human or agent — works from, outermost-in:

- [DESIGN-PRINCIPLES.md](DESIGN-PRINCIPLES.md) — how the code is designed and built; read first.
- [CONTRIBUTING.md](CONTRIBUTING.md) — conventions, the SDD workflow, definition of done.
- [CLAUDE.md](CLAUDE.md) — operational guardrails for agents, on top of the two above.
- [.claude/skills/sdd/SKILL.md](.claude/skills/sdd/SKILL.md) — the spec-driven development skill every change runs through.
- [GLOSSARY.md](GLOSSARY.md) — domain terms.
- [specs/](specs/) and [specs/templates/](specs/templates/) — the service's living docs and their templates; [ci/specs/](ci/specs/) — the pipeline's.

## Security & exposure

LAN + Tailscale only; no application authentication. No secrets are committed to the repository.
