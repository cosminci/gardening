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

Pesticides will appear in the UI as a multi-select list when action type "Pesticide" is selected. They appear as an expandable section when pressing the Pesticide button, and disappear if that action is deselected. Each pesticide exposes a colored `F`, `I`, or `T` type badge, an information control that shows its notes on hover, keyboard focus, or touch activation, and an Edit control.

Editing of both substrates and pesticides happens directly from their usage sites. The compact substrate actions Extend mix and Define new component share one row below the mix, with the former left-aligned and the more consequential catalog definition highlighted and right-aligned. Substrate rows omit redundant labels, place `%` inside the share input, and expose centered information and Edit controls beside the component dropdown. Pesticide choices appear after moisture, place the colored type badge before the name, and expose the same highlighted, right-aligned Define new pesticide action below the choices. Adding or editing opens an adjacent editor sheet while preserving the operation form.

Every side sheet header contains one concise title without a supertitle or explanatory subtitle. Catalog editors use a single-line name field and a multiline information field. Pesticide type is selected from the supported types rather than entered as free text. The Save action sits centered at the bottom of the editor. Each selected substrate component exposes an information control beside its dropdown that shows its notes on hover, keyboard focus, or touch activation.

The side sheets form a visible hierarchy and use the same red, right-pointing chevron collapse control. Opening an operation for logging or editing slides its sheet in from the right. When the rightmost editor exits, the operation sheet moves right into its place during the same transition. Collapsing the operation sheet while an editor is open then closes the operation sheet only after that editor transition completes; pressing Escape collapses only the rightmost open sheet.

## Acceptance Criteria

- Pesticides and substrate components are stored in their own tables in the database.
- Pesticides and substrate components are editable by the user.
- Catalog identifiers and editable text remain strongly modelled as opaque types, while pesticide type is a closed enum.
- Care operations reference zero or more selected pesticides by stable identifier.
- UI allows users to add and edit pesticides and substrate components directly from their action forms without a separate management page.
- Pesticide choices display a colored type badge and expose notes through an information control rather than inline text.
- Selected substrate components expose information and Edit controls beside their dropdown.
- Information controls reveal pesticide metadata and substrate-component notes on hover, keyboard focus, and touch activation.
- Operation sheets enter from the right. Side sheets exit to the right; while an editor exits, the operation sheet moves right into its place before optionally exiting itself, and every sheet uses the same red right-pointing chevron control.

## Out of Scope
- Deleting pesticides and substrate components. A later delete operation must reject items still referenced by a plant or operation.

## Doc Sync

- GLOSSARY.md — define nomenclature and pesticide; update substrate-component.
- specs/design.md — catalog domain model, references, editing rules, and usage-site UI behavior.
- specs/contracts.md — point to the generated catalog and operation-reference API contracts.
- specs/testing.md — catalog persistence fixtures and inline editing test boundaries.
