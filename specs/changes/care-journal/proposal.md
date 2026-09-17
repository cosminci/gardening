# Care journal — plants and their dated care log

> Standard: Agentic Engineering Standards v1.2.0
>
> Lifetime: open from creation through implementation, archived in the Sync & Archive step.

**Date:** 2026-09-15

## What

A browsable care journal for the household's plants.

- One row per active plant: fixed attributes (species, nickname, location), current substrate (a component mix), and recent operations.
- Operations can be logged and edited.
- Plant status is persisted for later archived-plant support.
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

sealed trait OperationDetails:
  def date: Instant
  def maybeNote: Option[Note]
final case class Care(override val date: Instant, actions: Set[ActionType], moisture: MoistureLevel, override val maybeNote: Option[Note]) extends OperationDetails
final case class Repot(override val date: Instant, substrate: Substrate, override val maybeNote: Option[Note]) extends OperationDetails

enum LogOperationResult:
  case Logged(id: OperationId)
  case LoggingFailed(reason: Throwable)

enum EditOperationResult:
  case Edited(operation: Operation)
  case OperationMissing
  case OperationTypeMismatch
  case Corrupted(details: NonEmptyList[JournalCorruption])
  case EditFailed(reason: Throwable)

enum JournalRecord:
  case Plant(id: PlantId)
  case Operation(id: OperationId)

final case class JournalCorruption(record: JournalRecord, reason: Throwable)

enum JournalReadResult[+A]:
  case Read(value: A)
  case RecordMissing
  case Corrupted(details: NonEmptyList[JournalCorruption])
  case ReadFailed(reason: Throwable)

trait PlantJournal:
  def getPlants: JournalReadResult[Vector[Plant]]
  def getOperations(plantId: PlantId): JournalReadResult[Vector[Operation]]
  def logOperation(plantId: PlantId, op: OperationDetails): LogOperationResult
  def editOperation(id: OperationId, details: OperationDetails): EditOperationResult

trait IdGenerator:
  def nextId(): String

trait PlantJournalStore:
  def getPlant(id: PlantId): JournalReadResult[Plant]
  def getPlants: JournalReadResult[Vector[Plant]] // live plants only
  def getOperations(plantId: PlantId): JournalReadResult[Vector[Operation]]
  def getOperation(id: OperationId): JournalReadResult[Operation]
  def addOperation(operation: Operation): LogOperationResult
  def updateOperation(id: OperationId, details: OperationDetails): EditOperationResult
  def updatePlant(plant: Plant): Unit

object PlantJournal:
  def make(using store: PlantJournalStore^, idGenerator: IdGenerator^): PlantJournal^{store, idGenerator}
```

`Substrate`, enforced at construction:

- Non-empty; components distinct; shares each a `Percentage` (1–100).
- Shares total at most 100 — a shortfall is an unspecified remainder, not an error.
- Rejected: total over 100, a repeated component, an empty mix.

A plant owns its current substrate. After successfully recording or amending a repot, the journal updates the affected plant's mix through a distinct store operation. Ordinary care operations leave the mix unchanged. Reads return the stored mix without reconstructing it from operation history.

Plants are not deleted. Every operation belongs to an existing plant and cannot outlive it.

Persistence treats operation details as one discriminated value. A stored value identifies its care or repot variant and contains only that variant's fields; persistence does not model the variants as one nullable-field product.

`PlantJournal` captures its store and identifier generator, making their authority explicit in the journal value's type and preventing it from escaping a shorter-lived capability scope.

## Frontend

Hexagonal, like the backend:

- Mirrors the backend domain one-to-one: branded ids/scalars, the same ADTs, and a `JournalClient` port whose methods match `PlantJournal` (async over the sync backend).
- Components depend on the port, never a concrete HTTP client; one adapter implements it, mapping transport errors into the `*Failed` result cases.
- Wire shapes are single-sourced from the OpenAPI contract — nothing hand-duplicated; components test against a stub of the port.
- A repot carries the new mix, so the mix editor lives in the operation form, not a separate screen.
- Archived-plant retrieval and presentation are deferred; the UI shows active plants only.

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
  | "watered" | "fertilized" | "pesticide" | "pruned" | "noAction";

type MoistureLevel = "wet" | "moderatePlus" | "moderateMinus" | "dry" | "noReading";

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

interface CareOperationDetails {
  readonly kind: "care";
  readonly date: Instant;
  readonly actions: ReadonlySet<ActionType>;
  readonly moisture: MoistureLevel;
  readonly notes: Note | null;
}

interface RepotOperationDetails {
  readonly kind: "repot";
  readonly date: Instant;
  readonly substrate: Substrate;
  readonly notes: Note | null;
}

type OperationDetails = CareOperationDetails | RepotOperationDetails;

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
  | { readonly kind: "operationTypeMismatch" }
  | { readonly kind: "corrupted"; readonly details: readonly [JournalCorruption, ...JournalCorruption[]] }
  | { readonly kind: "editFailed"; readonly reason: Error };

type JournalRecord =
  | { readonly kind: "plant"; readonly id: PlantId }
  | { readonly kind: "operation"; readonly id: OperationId };

interface JournalCorruption {
  readonly record: JournalRecord;
  readonly reason: Error;
}

type JournalReadResult<A> =
  | { readonly kind: "read"; readonly value: A }
  | { readonly kind: "recordMissing" }
  | { readonly kind: "corrupted"; readonly details: readonly [JournalCorruption, ...JournalCorruption[]] }
  | { readonly kind: "readFailed"; readonly reason: Error };

interface JournalClient {
  getPlants(): Promise<JournalReadResult<readonly Plant[]>>;
  getOperations(plantId: PlantId): Promise<JournalReadResult<readonly Operation[]>>;
  logOperation(plantId: PlantId, op: OperationDetails): Promise<LogOperationResult>;
  editOperation(id: OperationId, details: OperationDetails): Promise<EditOperationResult>;
}
```

## Acceptance criteria

- Active plants: one row each. Row shows species, location, nickname, and current substrate (component mix with percentages).
- Row shows the three most recent operations, oldest→newest, one cell each; a new operation shifts the row, keeping the latest three. A care cell reads `date — moisture level — action(s)`; a repot cell identifies the repot and its new mix. Notes are appended when present.
- Add via a form: date (prefilled today, editable), a care-operation type, and optional note. A care operation records a moisture level and zero or more action-types, allowing a moisture-only observation. A repot captures a required new substrate mix (components distinct, shares ≤ 100). Saving a repot records it, then updates the plant's current substrate.
- Any field except the operation type is editable through the same form. Cells are date-ranked, so editing a date can change which three show and their order; a repot edit updates the plant's current substrate after its operation is saved.
- Operations cannot be deleted, because each records care that has already happened.
- A care operation cannot carry a substrate mix; a repot always carries one.
- Substrate changes only via a repot — no standalone editor; the row reflects the plant's persisted current mix.
- An edit cannot change an operation between care and repot.
- Attempting to change an operation between care and repot reports an operation-type mismatch without changing the journal.
- Stored care and repot details round-trip according to their discriminator. Store reads report malformed JSON, discriminators, required fields, dates, enum values, and substrates as attributed corruption rather than valid domain values, accumulating independent failures within and across rows.
- Collection reads validate every persisted row before visibility filtering and report every corruption together, with each cause attributed to its plant or operation identifier; valid archived plants remain hidden, while malformed status data is never silently omitted.
- A missing plant or operation is reported as `RecordMissing`, distinct from a successful single-record read.
- Collection reads never report `RecordMissing`; an empty journal, including the operation log requested for an unknown plant, is `Read(Vector.empty)`.
- Database access failures are reported separately from stored-data corruption.
- First run is pre-populated: a one-time, idempotent import script loads every plant (attributes, substrate mix, full operation history) from the household spreadsheet, retired plants archived.

## Tradeoffs accepted

- A recorded real-world repot remains in the journal if an infrastructure failure prevents the subsequent current-substrate refresh. This change does not introduce a partial-success result or automatic repair workflow.

## Out of scope

- Archived-plant retrieval and presentation.
- Sorting the bounded household collections in persistence; the frontend sorts them for display.

## Doc Sync

- GLOSSARY.md — Plant, Location, Operation (replacing "Action"), Substrate, Substrate-component, Moisture-level, Action-type, Nickname, active/archived status, English-vocabulary + preserved-free-text handling.
- specs/design.md — service overview, domain model, processing rules, edge cases, invariants (substrate shares ≤ 100; English handling); component diagram.
- contracts.md — points at both, restating neither: `contract/openapi.yaml` (HTTP API) and the database schema (versioned Flyway migrations folder).
- specs/testing.md — testing methodology and test-type naming (unit, seam-integration); no system-integration tier here.
- specs/operational.md — runtime dependencies: on-disk SQLite (schema applied by migrations at startup); how the frontend is served and reaches the backend.
