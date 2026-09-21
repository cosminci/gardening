# Editable nomenclatures

> Standard: Agentic Engineering Standards v1.2.0
>
> Lifetime: open through implementation, then synced into living docs and archived.

**Date:** 2026-09-21

## What & Why

Substrate components are fixed in code, and insecticides have no editable catalog. Both need editable nomenclatures.

Substrates: populate from the initial enum.
Each substrate should have a name and info e.g. how to use them / water ratio etc.

Pesticides:
* F: ORTIVA TOP 1ml/L
* F: SWITCH 62.5 WG
* I: VERTAB 0.8ml/L
* I: SIMFONIA (organic)
* I: SPRUZIT AF Neudorff
* I: MOSPILAN 20SG
* I: Neem oil + Catille soap 5ml:5ml:1L
* H2O2
Each of these have should have a name, a type and info e.g. how to use them / water ratio etc.
In the list above - things like 0.8ml/L is not part of the name, but part of the info.

Name, type, info is all editable across substrate components & pesticides.
They will have a stable unique identifier (UUID) for reference in the database.

The domain model needs to adapt to support these.

Pesticides will appear in the UI as a list of selectable items, when action type "Pesticide" is selected. They appear as an expandable section when pressing the Pesticide button, and disappear if that action is deselected.

Editing of both substrates and pesticides should be fluent and natural. Rather than a separate "edit" section for them, the edit functionality should be integrated into their usage sites. For example, when a user is adding a pesticide action, they should be able to add a new pesticide or edit an existing one directly from the action form.

## Acceptance Criteria

- Pesticides and substrate components are stored in their own tables in the database.
- Pesticides and substrate components are editable by the user.
- Domain continues to model them strongly, but with opaque types rather than enums.
- UI allows users to add and edit pesticides and substrate components directly from the action forms.

## Out of Scope
- Deleting pesticides and substrate components. This is a separate discussion as it raises questions about how to handle existing actions and plants that reference deleted items.

## Doc Sync

- GLOSSARY.md — define nomenclature and pesticide; update substrate-component.
- specs/design.md — catalog domain model, references, editing rules, and usage-site UI behavior.
- specs/contracts.md — point to the generated catalog and operation-reference API contracts.
- specs/testing.md — catalog persistence fixtures and inline editing test boundaries.
