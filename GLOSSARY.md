# Glossary

> Standard: Agentic Engineering Standards v1.2.0

Domain terms for the house-plant care journal. Controlled vocabulary is presented in English; free-text species, nicknames, locations, and notes are preserved verbatim.

| Term | Meaning |
| --- | --- |
| **Plant** | A single house plant the household owns and the unit for which a journal is kept. |
| **Location** | The in-house place where a plant lives, such as a room or windowsill. |
| **Nickname** | An optional household name for a plant, distinct from its species. |
| **Plant status** | Whether a plant is active or archived. Active plants appear in the journal; archived status is retained for later archived-plant support. |
| **Operation** | A dated care event belonging to one plant. It is either care or a repot; its identifier, plant, timestamp, and kind do not change after logging, while its kind-specific details may be edited. |
| **Care operation** | An operation recording one moisture-level, zero or more action-types, zero or more referenced pesticides, and an optional note. |
| **Repot operation** | An operation recording the plant's new substrate and an optional note. The latest repot determines the plant's current substrate. |
| **Action-type** | A fixed English category of care performed, such as watering, fertilizing, pesticide treatment, or pruning. |
| **Moisture-level** | A fixed English observation of substrate moisture recorded with a care operation. |
| **Substrate** | A non-empty component mix describing the growing medium in which a plant currently sits. |
| **Nomenclature** | An editable catalog of named values that operations or plants reference by stable identifier. |
| **Substrate-component** | An editable nomenclature item used as an ingredient in a substrate mix, with a name and optional usage information. |
| **Pesticide** | An editable nomenclature item used by care operations, with a name, type, and optional usage information. |
