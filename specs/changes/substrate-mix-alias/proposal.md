# Substrate-mix aliases

> Standard: [Agentic Engineering Standards](https://github.com/Adobe-AIFoundations/agentic-workflow-standards) v1.2.0.
> Lifetime: open from creation through implementation, archived in the [sync docs & archive step](../../agentic-workflows.md#a-structured-development-skills).

**Date:** 2026-09-25

Adds a named, independently-saved substrate mix that can be saved from an in-progress edit, loaded to replace one, or permanently deleted.

## What & Why

A plant's substrate and a repot operation's substrate are built one component at a time, with no way to save a mix for reuse. A substrate-mix alias is a named, independently-saved mix with optional notes. It can be created from the mix currently being edited, loaded to replace that mix, or permanently deleted. Available wherever a substrate mix is edited: creating a plant, editing a plant's current substrate, and adding or editing a repot operation.

## Domain / Design Notes

A substrate-mix alias has a name, optional notes, and the components and shares it was saved with, with its own identity independent of any plant, operation, or substrate component.

Loading an alias copies its components and shares into the mix being edited rather than referencing the alias. Later edits or deletion of the alias never affect a plant or operation previously loaded from it.

Aliases support listing, adding, and permanently deleting. Renaming or changing an existing alias's saved composition is not yet supported.

The substrate-component catalog and the substrate-mix alias catalog share one HTTP boundary instead of two separate ones; today only substrate components have one.

Substrate components and pesticides currently share one generic name/info type across unrelated entities. This change removes that generic type: each catalog entity gets its own specifically-named name and info types, and the new alias entity follows the same rule rather than introducing another shared one.

### Domain type

```scala
final case class SubstrateMixAlias(id: UUID, name: SubstrateMixAliasName, notes: Option[SubstrateMixAliasNotes], substrate: Substrate)
```

`substrate` is the same `Substrate` type already used for `Plant.substrate` and a repot `Operation`'s substrate — an alias just persists that value under a name, independently of any plant or operation. `SubstrateComponentData` and `PesticideData` move from the shared generic name/info type to their own `SubstrateComponentName`/`SubstrateComponentInfo` and `PesticideName`/`PesticideInfo` types, the same shape `SubstrateMixAliasName`/`SubstrateMixAliasNotes` follow.

### Database schema (Flyway migration)

```sql
create table substrate_mix_alias (
    id text primary key check (length(id) = 36),
    name text not null,
    notes text,
    substrate text not null check (json_valid(substrate))
);
```

`substrate` stores the same JSON-encoded component/share list already used for `plant.substrate`, so an alias's mix is validated and decoded with the existing `Substrate` codec.

## Acceptance Criteria

- Saving the mix being edited creates a new alias with a name and optional notes, only while that mix is valid (components used once, whole-percent shares, total ≤ 100%); saving does not require the plant or operation itself to be saved.
- Saving is rejected when an alias with the exact same components and shares already exists.
- An alias's components and shares never change after saving, even if the source mix or its components are later edited.
- Loading an alias replaces every row of the mix being edited with the alias's components and shares; loading does not save the plant or operation.
- Save and load appear next to the action that extends the mix, separate from the action that defines a new component.
- The load view lists every saved alias with its name, notes, and components/shares, styled like a mix is shown elsewhere, so aliases can be told apart without opening each one.
- Loading an alias whose components are no longer all available is rejected with a clear reason; the mix being edited is unchanged.
- The load view states clearly when no aliases exist yet.
- The load view offers deleting an alias, guarded by the same irreversible-action confirmation used for archiving a plant and deleting an operation; cancelling leaves the alias unchanged.
- Deleting an alias always succeeds: no plant or operation depends on an alias continuing to exist.
- Aliases are stored in a new database table, added via a Flyway schema migration, alongside the existing substrate-component and pesticide catalogs.

## Doc Sync

- GLOSSARY.md — remove the generic "Nomenclature" term; reword the substrate-component and pesticide entries to stand on their own. Define substrate-mix alias: a named, independently-saved substrate mix with optional notes; unlike a substrate component, it may be permanently deleted.
- specs/design.md — domain model gains the substrate-mix alias entity, referencing substrate components, independent of any plant or operation, deletable without a reference check.
- specs/contracts.md — point to the regenerated contract; substrate components and substrate-mix aliases now sit under one shared HTTP resource instead of two.
- specs/testing.md — note the alias persistence boundary, the load-time rejection when saved components are unavailable, and that deletion is unconditional.

## Out of Scope

- Renaming or changing the saved composition of an existing substrate-mix alias.
