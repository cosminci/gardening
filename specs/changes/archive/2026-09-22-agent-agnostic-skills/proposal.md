# Agent-agnostic coding skills

## What & Why

The repository currently makes its development skill discoverable through one coding
agent's private configuration namespace. This couples shared engineering guidance to
that agent and forces other agents to discover or duplicate the same guidance.

After this change, repository skills are published from a neutral, conventional
location that any coding agent can inspect. Agent-specific configuration may remain
for local settings, but it is not the owner of shared skills or their documentation.
Claude, Copilot, and other agents therefore follow the same skill content and
workflow.

## Acceptance criteria

- A coding agent can discover the repository's shared skills without looking under
  an agent-specific configuration directory.
- The spec-driven development skill remains available with the same phases, gates,
  and behavior after the move.
- Repository orientation and contribution guidance point agents to the neutral skill
  location.
- No shared skill content remains owned by `.claude`.
- Existing agent-local settings remain local configuration and are not treated as
  shared skills.

## Invariants

- The repository continues to require spec-driven development for product changes.
- The five SDD phases and their quality gates remain unchanged.
- The repository's application code, generated contract, and plant import sources
  are unaffected.
- Agent-local permissions and settings do not become shared repository behavior.

## Tradeoffs

The neutral directory is intentionally a small repository convention rather than a
tool-specific integration. Agents that provide automatic skill discovery can use
it
directly; agents without that convention can still follow the linked repository
guidance without requiring duplicated skill files.

## Doc Sync

- Update `README.md` repository orientation to link to the neutral shared-skills
  location.
- Update `CONTRIBUTING.md` repository map and SDD workflow links to use the neutral
  location.
- Update `CLAUDE.md` orientation links so Claude consumes the shared skill rather
  than owning it.

## Workflow contract

The repository workflow uses separate pull requests for each SDD delivery stage:
the Spec PR contains the proposal and is merged before implementation; one or more
Implementation PRs deliver the approved behavior; and the Archive + Living Docs PR
archives the proposal and synchronizes living documentation after implementation
merges. The final PR contains no new product behavior.
