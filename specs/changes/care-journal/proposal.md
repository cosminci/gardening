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

final case class Plant(id: PlantId, details: PlantDetails)
final case class PlantDetails(species: Species, maybeNickname: Option[Nickname], location: Location, substrate: Substrate, status: PlantStatus)
final case class Operation(id: OperationId, plantId: PlantId, date: Instant, details: OperationDetails)

sealed trait OperationDetails:
  def maybeNote: Option[Note]
final case class Care(actions: Set[ActionType], moisture: MoistureLevel, override val maybeNote: Option[Note]) extends OperationDetails
final case class Repot(substrate: Substrate, override val maybeNote: Option[Note]) extends OperationDetails

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

enum JournalReadFailure:
  case RecordMissing
  case Corrupted(details: NonEmptyList[JournalCorruption])
  case ReadFailed(reason: Throwable)

trait PlantJournal:
  def getPlants: Either[JournalReadFailure, Vector[Plant]]
  def getOperations(plantId: PlantId): Either[JournalReadFailure, Vector[Operation]]
  def logOperation(plantId: PlantId, op: OperationDetails): LogOperationResult
  def editOperation(id: OperationId, details: OperationDetails): EditOperationResult

trait IdGenerator:
  def nextId(): String

trait PlantJournalStore:
  def getPlant(id: PlantId): Either[JournalReadFailure, Plant]
  def getPlants: Either[JournalReadFailure, Vector[Plant]] // live plants only
  def getOperations(plantId: PlantId): Either[JournalReadFailure, Vector[Operation]]
  def getOperation(id: OperationId): Either[JournalReadFailure, Operation]
  def addOperation(operation: Operation): LogOperationResult
  def updateOperation(id: OperationId, details: OperationDetails): EditOperationResult
  def removeOperation(id: OperationId): Either[Throwable, Unit]
  def restoreOperation(operation: Operation): Either[Throwable, Unit]
  def updatePlant(plant: Plant): Either[Throwable, Unit]

object PlantJournal:
  def make(using store: PlantJournalStore^, idGenerator: IdGenerator^, clock: Clock^): PlantJournal^{store, idGenerator, clock}
```

`Substrate`, enforced at construction:

- Non-empty; components distinct; shares each a `Percentage` (1–100).
- Shares total at most 100 — a shortfall is an unspecified remainder, not an error.
- Rejected: total over 100, a repeated component, an empty mix.

A plant owns its current substrate, which matches the substrate of its greatest-date repot; operation timestamps are immutable, backend-generated, and unique within a plant, so a newly recorded repot is the latest repot without reading operation history. Logging and editing workflows are serialized so their writes, plant synchronization, and compensation cannot interleave. Recording always writes the operation first, then a repot updates the plant substrate; a failed post-write plant read or update removes the new repot. Amending always writes the editable operation details first; if the operation is the latest repot, the journal then updates the plant substrate. A failed post-edit read or plant update restores the previous operation. The overall operation fails, and a failed compensation is also reported while retaining both causes. Editing an older repot does not update the plant. Ordinary care operations leave the mix unchanged. Reads return the stored mix without reconstructing it from operation history.

Plants are not deleted. Every operation belongs to an existing plant and cannot outlive it. Operations have no user-facing deletion capability; store-level removal exists only to compensate a failed repot log.

Persistence keeps common operation metadata relational: a constrained `kind` column identifies the care or repot variant, while a valid-JSON `payload` column contains only that variant's fields. This keeps the schema queryable and constrained without modelling variants as one nullable-field product or coupling its shape to the domain model.

`PlantJournal` captures its store, identifier generator, and clock, making their authority explicit in the journal value's type and preventing it from escaping a shorter-lived capability scope.

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

interface PlantDetails {
  readonly species: Species;
  readonly nickname: Nickname | null;
  readonly location: Location;
  readonly substrate: Substrate;
  readonly status: PlantStatus;
}

interface Plant {
  readonly id: PlantId;
  readonly details: PlantDetails;
}

interface CareOperationDetails {
  readonly kind: "care";
  readonly actions: ReadonlySet<ActionType>;
  readonly moisture: MoistureLevel;
  readonly notes: Note | null;
}

interface RepotOperationDetails {
  readonly kind: "repot";
  readonly substrate: Substrate;
  readonly notes: Note | null;
}

type OperationDetails = CareOperationDetails | RepotOperationDetails;

interface Operation {
  readonly id: OperationId;
  readonly plantId: PlantId;
  readonly date: Instant;
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
- Add via a form: a care-operation type and optional note. The backend assigns the immutable operation timestamp when logging. A care operation records a moisture level and zero or more action-types, allowing a moisture-only observation. A repot captures a required new substrate mix (components distinct, shares ≤ 100). Saving a latest repot records it, then updates the plant's current substrate.
- Editable operation-detail fields use the same form; operation type, identifier, plant, and timestamp are immutable. Editing the latest repot updates the plant's current substrate after its details are saved; editing an older repot leaves the plant unchanged.
- Operations cannot be deleted by users, because each records care that has already happened. A newly written repot is removed only when compensating a failed current-substrate update.
- A care operation cannot carry a substrate mix; a repot always carries one.
- Substrate changes only via a repot — no standalone editor; the row reflects the plant's persisted current mix.
- Logging a repot succeeds only when both the operation and the plant's current substrate match the resulting greatest-date repot. If the post-write plant read or substrate update fails, the new operation is removed and logging reports failure.
- Editing the latest repot succeeds only when both its amended details and the plant's current substrate are persisted. A pre-write read failure changes nothing; if the post-write substrate update fails, the previous operation is restored and editing reports failure. Editing an older repot never updates the plant.
- Logging and editing operation workflows do not interleave; one completes its synchronization or compensation before another begins.
- If repot compensation also fails, the overall failure reports both causes rather than returning success.
- An edit cannot change an operation between care and repot.
- Attempting to change an operation between care and repot reports an operation-type mismatch without changing the journal.
- Stored operation timestamps and care/repot details round-trip, with a relational discriminator and variant-specific JSON payload. Database constraints reject malformed JSON payloads and unknown discriminators; store reads report schema-accepted malformed timestamps, required fields, enum values, and substrates as attributed corruption rather than valid domain values, accumulating independent failures within and across rows.
- Collection reads validate every persisted row before visibility filtering and report every corruption together, with each cause attributed to its plant or operation identifier; valid archived plants remain hidden, while malformed status data is never silently omitted.
- A missing plant or operation is reported as `Left(RecordMissing)`, distinct from a successful single-record read.
- Collection reads never report `RecordMissing`; an empty journal, including the operation log requested for an unknown plant, is `Right(Vector.empty)`.
- Database access failures are reported separately from stored-data corruption.
- First run is pre-populated: a one-time, idempotent import script loads every plant (attributes, substrate mix, full operation history) from the household spreadsheet, retired plants archived.

## Tradeoffs accepted

- Compensation can itself fail after the primary substrate-update failure. The journal reports both causes explicitly; automated repair beyond the immediate compensation attempt and process-crash recovery between saga steps are deferred.

## Out of scope

- Archived-plant retrieval and presentation.
- Sorting the bounded household collections in persistence; the frontend sorts them for display.

## Doc Sync

- GLOSSARY.md — Plant, Location, Operation (replacing "Action"), Substrate, Substrate-component, Moisture-level, Action-type, Nickname, active/archived status, English-vocabulary + preserved-free-text handling.
- specs/design.md — service overview, domain model, processing rules, edge cases, invariants (substrate shares ≤ 100; English handling); component diagram.
- contracts.md — points at both, restating neither: `contract/openapi.yaml` (HTTP API) and the database schema (versioned Flyway migrations folder).
- specs/testing.md — testing methodology and test-type naming (unit, seam-integration); no system-integration tier here.
- specs/operational.md — runtime dependencies: on-disk SQLite (schema applied by migrations at startup); how the frontend is served and reaches the backend.
