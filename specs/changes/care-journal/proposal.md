# Care journal — plants and their dated care log

> Standard: Agentic Engineering Standards v1.2.0
>
> Lifetime: open from creation through implementation, archived in the Sync & Archive step.

**Date:** 2026-09-15

## What

A browsable care journal for the household's plants.

- One row per active plant: fixed attributes (species, nickname, location), current substrate (a component mix), and recent operations.
- Operations can be logged, edited, and removed.
- Retired plants sit in a separate archived section.
- Controlled vocabulary (substrate components, action-types, moisture levels) is English; imported free text is preserved verbatim.

## Domain

```scala
opaque type PlantId     = String
opaque type OperationId = String
opaque type Species     = String
opaque type Nickname    = String
opaque type Location    = String
opaque type Note        = String
opaque type Percentage  = Int // 1..100 - enforced via smart constructor

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

enum MoistureLevel(val label: String):
  case Wet           extends MoistureLevel("Wet")
  case ModeratePlus  extends MoistureLevel("Moderate +")
  case ModerateMinus extends MoistureLevel("Moderate -")
  case Dry           extends MoistureLevel("Dry")
  case NoReading     extends MoistureLevel("N/A")

final case class SubstratePart(component: SubstrateComponent, share: Percentage)
opaque type Substrate = List[SubstratePart]

final case class Plant(id: PlantId, species: Species, nickname: Option[Nickname], location: Location, substrate: Substrate, status: PlantStatus)
final case class Operation(id: OperationId, plantId: PlantId, details: OperationDetails)
final case class OperationDetails(date: Instant, actions: Set[ActionType], moisture: MoistureLevel, substrate: Option[Substrate], notes: Option[Note])

enum LogOperationResult:
  case Logged(id: OperationId)
  case LoggingFailed(reason: Throwable)

enum EditOperationResult:
  case Edited(operation: Operation)
  case EditFailed(reason: Throwable)
  case OperationMissing

enum RemoveOperationResult:
  case Removed(operation: Operation)
  case AlreadyRemoved
  case RemoveFailed(reason: Throwable)

trait PlantJournal:
  def getPlants: Vector[Plant] 
  def getOperations(plantId: PlantId): Vector[Operation]
  def logOperation(plantId: PlantId, op: OperationDetails): LogOperationResult
  def editOperation(id: OperationId, details: OperationDetails): EditOperationResult
  def removeOperation(id: OperationId): RemoveOperationResult

trait PlantJournalStore:
  def getPlants: Vector[Plant] // live plants only
  def getOperations(plantId: PlantId): Vector[Operation]
  def addOperation(operation: Operation): LogOperationResult
  def updateOperation(id: OperationId, details: OperationDetails): EditOperationResult
  def deleteOperation(id: OperationId): RemoveOperationResult
```

`Substrate`, enforced at construction:

- Non-empty; components distinct; shares each a `Percentage` (1–100).
- Shares total at most 100 — a shortfall is an unspecified remainder, not an error.
- Rejected: total over 100, a repeated component, an empty mix.

A plant's current substrate is the mix from its most recent repot, or the import mix if never repotted. It changes only through a repot, never on its own.

## Frontend

Hexagonal, like the backend:

- Mirrors the backend domain one-to-one: branded ids/scalars, the same ADTs, and a `JournalClient` port whose methods match `PlantJournal` (async over the sync backend).
- Components depend on the port, never a concrete HTTP client; one adapter implements it, mapping transport errors into the `*Failed` result cases.
- Wire shapes are single-sourced from the OpenAPI contract — nothing hand-duplicated; components test against a stub of the port.
- On a repot, `OperationDetails.substrate` carries the new mix, so the mix editor lives in the operation form, not a separate screen.
- `getPlants` returns every plant; the UI splits active from archived by status.

```ts
type PlantId     = string & { readonly brand: "PlantId" };
type OperationId = string & { readonly brand: "OperationId" };
type Species     = string & { readonly brand: "Species" };
type Nickname    = string & { readonly brand: "Nickname" };
type Location    = string & { readonly brand: "Location" };
type Note        = string & { readonly brand: "Note" };
type Instant     = string & { readonly brand: "Instant" };
type Percentage  = number & { readonly brand: "Percentage" };

type PlantStatus = "active" | "archived";

type SubstrateComponent =
  | "kekkilaUniversal" | "kekkilaEricaceous" | "perlite" | "pineBark"
  | "sand3to5" | "sand4to8" | "leca";

type ActionType =
  | "watered" | "fertilized" | "repotted" | "pesticide" | "pruned" | "noAction";

type MoistureLevel =
  | "wet" | "moderatePlus" | "moderateMinus" | "dry" | "noReading";

interface SubstratePart {
  readonly component: SubstrateComponent;
  readonly share: Percentage;
}
type Substrate = readonly SubstratePart[] & { readonly brand: "Substrate" };

interface Plant {
  readonly id: PlantId;
  readonly species: Species;
  readonly nickname: Nickname | null;
  readonly location: Location;
  readonly substrate: Substrate;
  readonly status: PlantStatus;
}

interface OperationDetails {
  readonly date: Instant;
  readonly actions: ReadonlySet<ActionType>;
  readonly moisture: MoistureLevel;
  readonly substrate: Substrate | null;
  readonly notes: Note | null;
}

interface Operation {
  readonly id: OperationId;
  readonly plantId: PlantId;
  readonly details: OperationDetails;
}

type LogOperationResult =
  | { readonly kind: "logged"; readonly id: OperationId }
  | { readonly kind: "loggingFailed"; readonly reason: Error };

type EditOperationResult =
  | { readonly kind: "edited"; readonly operation: Operation }
  | { readonly kind: "operationMissing" }
  | { readonly kind: "editFailed"; readonly reason: Error };

type RemoveOperationResult =
  | { readonly kind: "removed"; readonly operation: Operation }
  | { readonly kind: "alreadyRemoved" }
  | { readonly kind: "removeFailed"; readonly reason: Error };

interface JournalClient {
  getPlants(): Promise<readonly Plant[]>;
  getOperations(plantId: PlantId): Promise<readonly Operation[]>;
  logOperation(plantId: PlantId, op: OperationDetails): Promise<LogOperationResult>;
  editOperation(id: OperationId, details: OperationDetails): Promise<EditOperationResult>;
  removeOperation(id: OperationId): Promise<RemoveOperationResult>;
}
```

## Acceptance criteria

- Active plants: one row each, ordered by location, then species, then nickname. Row shows species, location, nickname, and current substrate (component mix with percentages).
- Row shows the three most recent operations, oldest→newest, one cell each; a new operation shifts the row, keeping the latest three. Cell reads `date — moisture level — action(s)`, note appended when present.
- Add via a form: date (prefilled today, editable), one or more action-types, a moisture level, optional note. A repot also captures the new substrate mix (components distinct, shares ≤ 100). Saving appends and updates the row.
- Any field is editable through the same form, or the operation removable. Cells are date-ranked, so editing a date can change which three show and their order.
- Substrate changes only via a repot — no standalone editor; the row reflects the latest repot, or the import mix.
- Retired ("dead") plants show only in the archived section, never the active table.
- First run is pre-populated: a one-time, idempotent import script loads every plant (attributes, substrate mix, full operation history) from the household spreadsheet, retired plants archived.

## Doc Sync

- GLOSSARY.md — Plant, Location, Operation (replacing "Action"), Substrate, Substrate-component, Moisture-level, Action-type, Nickname, active/archived status, English-vocabulary + preserved-free-text handling.
- specs/design.md — service overview, domain model, processing rules, edge cases, invariants (substrate shares ≤ 100; English handling); component diagram.
- contracts.md — points at both, restating neither: `contract/openapi.yaml` (HTTP API) and the database schema (versioned Flyway migrations folder).
- specs/testing.md — testing methodology and test-type naming (unit, seam-integration); no system-integration tier here.
- specs/operational.md — runtime dependencies: on-disk SQLite (schema applied by migrations at startup); how the frontend is served and reaches the backend.
