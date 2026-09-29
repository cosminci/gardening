# Glossary

> Standard: Agentic Engineering Standards v1.2.0

Domain terms for the house-plant care journal. Controlled vocabulary is presented in English; free-text species, nicknames, locations, and notes are preserved verbatim.

| Term | Meaning |
| --- | --- |
| **Plant** | A single house plant the household owns and the unit for which a journal is kept. |
| **Location** | The in-house place where a plant lives, such as a room or windowsill. |
| **Nickname** | An optional household name for a plant, distinct from its species. |
| **Plant status** | Whether a plant belongs to the active garden or the permanently archived cemetery. |
| **Operation** | A dated care event belonging to one plant. It is either care or a repot; its identifier, plant, timestamp, and kind do not change after logging, while its kind-specific details may be edited. It may also be permanently deleted, except a plant's current latest repot. |
| **Recorded care range** | The earliest and latest recorded operation dates for a plant, not its archive date or a known date of death. The range is unknown when the plant has no operations. |
| **Care operation** | An operation recording one moisture-level, zero or more action-types, selected pesticides when applicable, and an optional note. |
| **Repot operation** | An operation recording the plant's new substrate and an optional note. The latest repot determines the plant's current substrate. |
| **Action-type** | A fixed English category of care performed, such as watering, showering, fertilizing, pesticide treatment, or pruning. |
| **Moisture-level** | A fixed English observation of substrate moisture recorded with a care operation. |
| **Substrate** | A non-empty component mix describing the growing medium in which a plant currently sits. |
| **Substrate-component** | An editable catalog entry with a stable identifier, name, and optional usage information, selected into a substrate mix with a percentage share. |
| **Substrate mix** | A named, independently-saved substrate mix with optional notes, saved from or loaded into a plant's or repot operation's substrate. Unlike a substrate component, it may be permanently deleted; later edits or deletion never affect a substrate previously loaded from it. |
| **Pesticide** | An editable catalog entry with a stable identifier, name, and optional usage information, that is a fungicide, insecticide, or treatment and may be selected by a care operation. |
| **Plant attention** | A periodic watering assessment associated with an active Plant by identifier, separate from its current details; the browser uses it for presentation order and warnings. |
| **Watering cadence** | The arithmetic mean of consecutive timestamps among a Plant's latest bounded watering sample. Cadence is unavailable until five waterings exist. |
| **Urgency** | The exact ratio of elapsed time since the latest watering to the inferred watering cadence. A zero cadence is unbounded after time advances. |
| **Overdue** | A watering state reached immediately after elapsed time exceeds the inferred cadence. |
| **Red alert** | A watering state reached when elapsed time is at least the inferred cadence plus 24 hours. |
