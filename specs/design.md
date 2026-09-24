# plant-journal design

> Standard: Agentic Engineering Standards v1.2.0

## Overview

The journal owns plant identity and dated care history. Watering attention is a periodically refreshed projection, joined to current plant details only for presentation.

## Domain model

```mermaid
flowchart LR
  Plant -->|has history| Operation
  Operation --> Care & Repot
  Plant -->|current mix| Substrate
  Repot -->|replaces mix| Substrate
  Substrate -->|references| Component["Substrate component"]
  Care -->|may reference| Pesticide
  Waterings["Watering history"] --> Attention["Watering attention"]
  Care --> Waterings
  Attention -->|by plant identity| Plant
```

- Creation establishes the plant's initial mix independently of operation history; the latest repot by recorded date and identifier replaces it as the current mix, not the order of entry. Catalog identities remain stable across edits so historical references keep their meaning.
- Archiving keeps plant history and its care-date range but prevents new operations; existing operation details may still be corrected without changing their date or kind.

## Use cases and workflows

```mermaid
flowchart LR
  Browser -->|create with initial mix| Journal["Plant journal"]
  Browser -->|read and log operations| Journal
  Journal -->|persist| Storage["Journal and catalogs"]
  Journal -->|watering history| Monitor["Attention measurement"]
  Monitor -->|complete snapshot| Browser
  Journal -->|current plant details| Browser
  Browser -->|join by identity| Garden["Garden / cemetery"]
```

- Operation mutations are serialized. A latest-repot change that fails to update the plant compensates the operation write and preserves both failures if compensation also fails.
- The garden loads active plants and an archived count; the cemetery loads archived histories only when opened. After creation, the garden joins the new plant to the last complete attention projection and shows unavailable cadence until it has enough watering history. Unrelated attention identity mismatches fail the garden load; a recently archived plant may still appear in the last measurement.
- Garden plants without a measured cadence appear first in their existing order; the rest are ordered by time remaining until their next expected watering, most overdue first. A plant editor can close its nested substrate editor before closing itself, with each sheet sliding independently.
- An initial attention read must succeed to serve the app. Later refresh failures keep the last complete projection; insufficient watering history leaves cadence unavailable.
