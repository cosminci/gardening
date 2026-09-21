# Editable nomenclatures

> Standard: Agentic Engineering Standards v1.2.0
>
> Lifetime: open through implementation, then synced into living docs and archived.

**Date:** 2026-09-21

## What & Why

Substrate components are fixed in code, and insecticides have no editable catalog. Both need editable nomenclatures.

Substrates: populate from the initial enum.
Each substrate should have an editable name and optional info e.g. how to use it / water ratio etc.

Pesticides:
* F: ORTIVA TOP 1ml/L
* F: SWITCH 62.5 WG
* I: VERTAB 0.8ml/L
* I: SIMFONIA (organic)
* I: SPRUZIT AF Neudorff
* I: MOSPILAN 20SG
* I: Neem oil + Catille soap 5ml:5ml:1L
* H2O2
Each should have an editable name, a type selected from the `Fungicide`, `Insecticide`, or `Treatment` enum, and optional info e.g. how to use it / water ratio etc.
In the list above - things like 0.8ml/L is not part of the name, but part of the info.
The initial types are Fungicide for `F`, Insecticide for `I`, and Treatment for H2O2.

They will have a stable unique identifier (UUID) for reference in the database.

The domain model needs to adapt to support these.

Pesticides will appear in the UI as a multi-select list when action type "Pesticide" is selected. They appear as an expandable section when pressing the Pesticide button, and disappear if that action is deselected. Each pesticide exposes an information control that shows its type and notes on hover or keyboard focus.

Editing of both substrates and pesticides should be fluent and natural. Their usage sites expose a compact Manage action that opens an adjacent catalog sheet. The catalog sheet presents every entry in a read-only table with an Edit action and supports adding an entry. Adding or editing opens another adjacent editor sheet while preserving the operation form and catalog table.

Catalog editors use a single-line name field and a multiline information field. Pesticide type is selected from the supported types rather than entered as free text. The Save action sits centered at the bottom of the editor. Each selected substrate component exposes an information control beside its dropdown that shows its notes on hover or keyboard focus.

The side sheets form a visible hierarchy and use the same collapse control. Collapsing any sheet also collapses every sheet to its right; pressing Escape collapses only the rightmost open sheet.

## Acceptance Criteria

- Pesticides and substrate components are stored in their own tables in the database.
- Pesticides and substrate components are editable by the user.
- Catalog identifiers and editable text remain strongly modelled as opaque types, while pesticide type is a closed enum.
- Care operations reference zero or more selected pesticides by stable identifier.
- UI allows users to add and edit pesticides and substrate components directly from the action forms.
- Catalog managers display entries in a read-only table and open a separate editor sheet for additions and edits.
- Information controls reveal pesticide metadata and substrate-component notes on hover and keyboard focus.
- Side-sheet collapse controls are consistent, and collapsing a parent removes all descendant sheets.

## Out of Scope
- Deleting pesticides and substrate components. A later delete operation must reject items still referenced by a plant or operation.

## Doc Sync

- GLOSSARY.md — define nomenclature and pesticide; update substrate-component.
- specs/design.md — catalog domain model, references, editing rules, and usage-site UI behavior.
- specs/contracts.md — point to the generated catalog and operation-reference API contracts.
- specs/testing.md — catalog persistence fixtures and inline editing test boundaries.
