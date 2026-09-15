# Care journal — plants and their dated care log

> Standard: Agentic Engineering Standards v1.2.0
>
> Lifetime: open from creation through implementation, archived in the Sync & Archive step.

**Date:** 2026-09-10

## What

The household's plants become a browsable care journal. Each active plant is a row showing its fixed attributes (species, nickname, location), its current substrate as a mix of components, and its most recent care operations, with the ability to log, edit, and remove operations — repotting being how a plant's substrate mix changes. Retired plants sit in a separate archived section. The controlled vocabulary — substrate components, action-types, and substrate-characteristics — is in English; imported free text (species, nickname, location, note) is preserved as written.

## Domain

```scala
opaque type PlantId     = String
opaque type OperationId = String
opaque type Species     = String
opaque type Nickname    = String
opaque type Location    = String
opaque type Note        = String
opaque type Percentage  = Int

enum PlantStatus:
  case Active, Archived

enum SubstrateComponent(val label: String):
  case KekkilaUniversal  extends SubstrateComponent("Kekkila universal peat")
  case KekkilaEricaceous extends SubstrateComponent("Kekkila ericaceous peat")
  case Perlite           extends SubstrateComponent("Perlite")
  case PineBark          extends SubstrateComponent("Pine bark")
  case Sand3to5          extends SubstrateComponent("Sand 3-5 mm")
  case Sand4to8          extends SubstrateComponent("Sand 4-8 mm")
  case Leca              extends SubstrateComponent("LECA")

enum ActionType(val label: String):
  case Watered    extends ActionType("Watered")
  case Fertilized extends ActionType("Fertilized")
  case Repotted   extends ActionType("Repotted")
  case Pesticide  extends ActionType("Insecticide / H2O2")
  case Pruned     extends ActionType("Pruned")
  case NoAction   extends ActionType("None")

enum SubstrateCharacteristic(val label: String):
  case Wet           extends SubstrateCharacteristic("Wet")
  case ModeratePlus  extends SubstrateCharacteristic("Moderate +")
  case ModerateMinus extends SubstrateCharacteristic("Moderate -")
  case Dry           extends SubstrateCharacteristic("Dry")
  case NoReading     extends SubstrateCharacteristic("N/A")

final case class SubstratePart(component: SubstrateComponent, share: Percentage)
opaque type Substrate = List[SubstratePart]

final case class Plant(id: PlantId, species: Species, nickname: Option[Nickname],
                       location: Location, substrate: Substrate, status: PlantStatus)
final case class Operation(id: OperationId, plantId: PlantId, date: Instant,
                           actions: Set[ActionType], characteristic: SubstrateCharacteristic,
                           substrate: Option[Substrate], note: Option[Note])
final case class OperationDetails(date: Instant, actions: Set[ActionType],
                                  characteristic: SubstrateCharacteristic,
                                  substrate: Option[Substrate], note: Option[Note])

enum EditResult:
  case Edited(operation: Operation)
  case OperationMissing

enum RemoveResult:
  case Removed
  case OperationMissing

trait CareJournal:
  def getPlants(status: PlantStatus): Vector[Plant]
  def getOperations(plantId: PlantId): Vector[Operation]
  def logOperation(plantId: PlantId, details: OperationDetails): Operation
  def editOperation(id: OperationId, details: OperationDetails): EditResult
  def removeOperation(id: OperationId): RemoveResult

trait JournalStore:
  def getPlants(status: PlantStatus): Vector[Plant]
  def getOperations(plantId: PlantId): Vector[Operation]
  def addPlant(plant: Plant): Unit
  def updatePlantSubstrate(plantId: PlantId, substrate: Substrate): Unit
  def addOperation(operation: Operation): Unit
  def updateOperation(id: OperationId, details: OperationDetails): EditResult
  def deleteOperation(id: OperationId): RemoveResult
```

A `Substrate` is non-empty, its components are distinct, and its shares — each a `Percentage` (1–100) — total at most 100; a shortfall is an unspecified remainder, so a mix need not be fully accounted for. A total over 100, a repeated component, or an empty mix is rejected at construction. A plant's current substrate is the mix carried by its most recent repot operation, or the mix set at import if it has never been repotted; it changes only through a repot operation, never on its own.

## Frontend

The frontend is hexagonal like the backend: a small set of view-models, a `JournalClient` port that the components depend on (never a concrete HTTP client), and one adapter that implements it. The view-models and wire types are generated from the OpenAPI contract, so the frontend and backend never hand-duplicate a shape, and the components stay testable against a stub of the port. An `OperationDraft` carries the new substrate mix when the operation is a repot, so the mix editor lives inside the operation form rather than on a separate screen.

```ts
interface JournalClient {
  activePlants(): Promise<readonly Plant[]>;
  archivedPlants(): Promise<readonly Plant[]>;
  logOperation(plantId: string, draft: OperationDraft): Promise<Operation>;
  editOperation(operationId: string, draft: OperationDraft): Promise<Operation>;
  deleteOperation(operationId: string): Promise<void>;
}
```

## Acceptance criteria

- The main view lists one row per active plant, ordered by location, then species, then nickname; each row shows species, location, nickname, and its current substrate as a component mix with each component's percentage.
- A row shows its three most recent operations as three separate cells, oldest to newest; logging a new operation adds a cell and shifts the row so only the three most recent remain. Each cell reads `date — characteristic — action(s)`, with the note appended when present.
- A row can add an operation through a form — date prefilled to today (editable), one or more action-types, a substrate-characteristic, and an optional note; when the operation is a repot, the form also captures the new substrate mix (components distinct, shares totalling at most 100). Saving appends the operation and the row updates.
- An existing operation's date, action-types, characteristic, substrate mix (if a repot), and note can be edited through the same form, or the operation removed; because the recent cells are chosen by date, editing a date can change which three appear and their order.
- A plant's substrate changes only through a repot operation — there is no standalone substrate editor; the row's substrate reflects the most recent repot, or the mix set at import.
- Retired ("dead") plants appear only in a separate archived section, never in the active table.
- On first run the app is already populated: a one-time import script loads every plant with its attributes, substrate mix, and full operation history from the household spreadsheet, with retired plants archived; re-running it reproduces the same result.

## Doc Sync

- GLOSSARY.md — Plant, Location, Operation (replacing "Action"), Substrate, Substrate-component, Substrate-characteristic, Action-type, Nickname, and the active/archived status, plus the English-vocabulary / preserved-free-text handling.
- specs/design.md — service overview, domain model, processing rules, edge cases, and invariants (substrate shares total at most 100; English handling), with a component diagram.
- contracts.md — points at the two machine-checked contracts, restating neither: `contract/openapi.yaml` (the HTTP API — list active/archived plants; create, edit, and delete an operation, a repot carrying the substrate mix) and the database schema, defined by its versioned migrations folder.
- specs/testing.md — the testing methodology and the test-type naming (unit, seam-integration); this feature adds no system-integration tier.
- specs/operational.md — runtime dependencies: the on-disk SQLite database (its schema applied by versioned migrations at startup) and how the frontend is served and reaches the backend.
