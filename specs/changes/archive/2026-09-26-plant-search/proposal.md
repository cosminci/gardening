# Plant search

> Standard: [Agentic Engineering Standards](https://github.com/Adobe-AIFoundations/agentic-workflow-standards) v1.2.0.
> Lifetime: open from creation through implementation, archived in the [sync docs & archive step](../../agentic-workflows.md#a-structured-development-skills).

**Date:** 2026-09-26

Add a header search box that filters the visible plant list by nickname, species, or location.

## What & Why

Finding a specific plant today means scanning the whole Garden or Cemetery list. Inventory sits around 50 plants and is expected to grow to 60–70, never large enough to need a search engine or backend index — an instant client-side filter over the already-loaded list is enough.

A live, case-insensitive substring filter narrows the active view as the user types:

```
matches(plant, query) =
  query.isBlank
  || [nickname, species, location].any(field => field.containsIgnoreCase(query))
```

Header order changes to read left to right as identity, then status, then actions:

```
before:  Plant Journal ··········· Backend status   Add plant  Garden(N)  Cemetery(N)
after:   Plant Journal  Backend status ··········· Add plant  Search  Garden(N)  Cemetery(N)
```

## Alternatives Considered

- Backend search endpoint/index — rejected: at 50–70 plants a full client-side scan is instant, so a server round trip would only add latency and a new HTTP surface for no benefit.

## Invariants

- Search only changes which already-loaded plants are displayed; a plant's Garden/Cemetery membership and archived status are unaffected.

## Tradeoffs Accepted

- No fuzzy or typo-tolerant matching — a substring miss (typo, split word) simply won't match. Acceptable at this inventory size, where scanning the unfiltered list is already fast.

## Acceptance Criteria

- Backend connection status sits immediately after "Plant Journal"; the search box sits between "Add plant" and the Garden/Cemetery toggle. Both the search box and the toggle appear only once the journal has loaded, matching "Add plant" today.
- Typing filters the active view live, no submit step; matches are case-insensitive substring matches against nickname, species, or location. An empty or whitespace-only query shows every plant in the view, matching today's behavior.
- Switching Garden ⇄ Cemetery clears the query, so each view starts unfiltered; the query is in-memory only and does not survive a page reload. The Garden/Cemetery toggle counts always show each view's total plant count, unaffected by the filter.
- When the active view's filtered result is empty, an accessible empty-state message replaces the plant list.

## Doc Sync

No living doc changes: search is client-side presentation filtering over data already covered by the existing plant contract; it adds no domain type, port, HTTP surface, schema, or browser-only testing concern beyond what `design.md`, `contracts.md`, and `testing.md` already state.
