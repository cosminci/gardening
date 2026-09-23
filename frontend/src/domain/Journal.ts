type Brand<Value, Name extends string> = Value & { readonly __brand: Name };

export type PlantId = Brand<string, "PlantId">;
export type OperationId = Brand<string, "OperationId">;
export type Species = Brand<string, "Species">;
export type Nickname = Brand<string, "Nickname">;
export type Location = Brand<string, "Location">;
export type Note = Brand<string, "Note">;
export type Instant = Brand<string, "Instant">;
export type Duration = Brand<string, "Duration">;
export type Percentage = Brand<number, "Percentage">;
export type SubstrateComponentId = Brand<string, "SubstrateComponentId">;
export type PesticideId = Brand<string, "PesticideId">;
export type NomenclatureName = Brand<string, "NomenclatureName">;
export type NomenclatureInfo = Brand<string, "NomenclatureInfo">;

export const plantId = (value: string): PlantId => value as PlantId;
export const operationId = (value: string): OperationId => value as OperationId;
export const species = (value: string): Species => value as Species;
export const nickname = (value: string): Nickname => value as Nickname;
export const location = (value: string): Location => value as Location;
export const note = (value: string): Note => value as Note;
export const instant = (value: string): Instant => value as Instant;
export const duration = (value: string): Duration => value as Duration;
export const percentage = (value: number): Percentage => value as Percentage;
export const substrateComponentId = (value: string): SubstrateComponentId =>
  value as SubstrateComponentId;
export const pesticideId = (value: string): PesticideId => value as PesticideId;
export const nomenclatureName = (value: string): NomenclatureName => value as NomenclatureName;
export const nomenclatureInfo = (value: string): NomenclatureInfo => value as NomenclatureInfo;

export const pesticideTypes = ["fungicide", "insecticide", "treatment"] as const;
export type PesticideType = (typeof pesticideTypes)[number];

export const plantStatuses = ["active", "archived"] as const;
export type PlantStatus = (typeof plantStatuses)[number];

export const actionTypes = ["watered", "fertilized", "pesticide", "pruned", "noAction"] as const;
export type ActionType = (typeof actionTypes)[number];

export const moistureLevels = ["wet", "moderatePlus", "moderateMinus", "dry", "noReading"] as const;
export type MoistureLevel = (typeof moistureLevels)[number];

export interface SubstratePart {
  readonly component: SubstrateComponentId;
  readonly share: Percentage;
}

export type Substrate = Brand<readonly SubstratePart[], "Substrate">;
export const substrate = (parts: readonly SubstratePart[]): Substrate => parts as Substrate;

export interface SubstrateComponentData {
  readonly name: NomenclatureName;
  readonly maybeInfo: NomenclatureInfo | null;
}

export interface SubstrateComponent {
  readonly id: SubstrateComponentId;
  readonly data: SubstrateComponentData;
}

export interface PesticideData {
  readonly name: NomenclatureName;
  readonly pesticideType: PesticideType;
  readonly maybeInfo: NomenclatureInfo | null;
}

export interface Pesticide {
  readonly id: PesticideId;
  readonly data: PesticideData;
}

export interface PlantDetails {
  readonly species: Species;
  readonly maybeNickname: Nickname | null;
  readonly location: Location;
  readonly substrate: Substrate;
  readonly status: PlantStatus;
}

export interface Plant {
  readonly id: PlantId;
  readonly details: PlantDetails;
}

export type Urgency =
  | { readonly kind: "finite"; readonly numeratorNanos: string; readonly denominatorNanos: string }
  | { readonly kind: "unbounded" };

export type WateringState = "current" | "overdue" | "redAlert";

export type WateringCadence =
  | {
      readonly kind: "unavailable";
      readonly sampleCount: number;
      readonly maybeElapsed: Duration | null;
    }
  | {
      readonly kind: "inferred";
      readonly sampleCount: number;
      readonly averageInterval: Duration;
      readonly elapsed: Duration;
      readonly urgency: Urgency;
      readonly state: WateringState;
    };

export interface PlantAttention {
  readonly plant: Plant;
  readonly cadence: WateringCadence;
}

export interface AttentionProjection {
  readonly measuredAt: Instant;
  readonly plants: readonly PlantAttention[];
}

export interface CareOperationDetails {
  readonly kind: "care";
  readonly actions: ReadonlySet<ActionType>;
  readonly pesticides: ReadonlySet<PesticideId>;
  readonly moisture: MoistureLevel;
  readonly maybeNote: Note | null;
}

export interface RepotOperationDetails {
  readonly kind: "repot";
  readonly substrate: Substrate;
  readonly maybeNote: Note | null;
}

export type OperationDetails = CareOperationDetails | RepotOperationDetails;

export interface Operation {
  readonly id: OperationId;
  readonly plantId: PlantId;
  readonly date: Instant;
  readonly details: OperationDetails;
}

export interface OperationWindow {
  readonly offset: number;
  readonly size: number;
}

export interface OperationPage {
  readonly operations: readonly Operation[];
  readonly hasNextPage: boolean;
}

export type GetPlantsResult =
  | { readonly kind: "read"; readonly plants: readonly Plant[] }
  | { readonly kind: "readFailed"; readonly reason: Error };

export type GetAttentionResult =
  | { readonly kind: "read"; readonly projection: AttentionProjection }
  | { readonly kind: "readFailed"; readonly reason: Error };

export type GetOperationsResult =
  | { readonly kind: "read"; readonly page: OperationPage }
  | { readonly kind: "readFailed"; readonly reason: Error };

export type LogOperationResult =
  | { readonly kind: "logged"; readonly id: OperationId }
  | { readonly kind: "loggingFailed"; readonly reason: Error };

export type EditOperationResult =
  | { readonly kind: "edited"; readonly operation: Operation }
  | { readonly kind: "operationMissing" }
  | { readonly kind: "operationTypeMismatch" }
  | { readonly kind: "editFailed"; readonly reason: Error };

export type CatalogReadResult<A> =
  | { readonly kind: "read"; readonly entries: readonly A[] }
  | { readonly kind: "readFailed"; readonly reason: Error };

export type CatalogAddResult<A> =
  | { readonly kind: "added"; readonly entry: A }
  | { readonly kind: "addFailed"; readonly reason: Error };

export type CatalogEditResult<A> =
  | { readonly kind: "edited"; readonly entry: A }
  | { readonly kind: "recordMissing" }
  | { readonly kind: "editFailed"; readonly reason: Error };

export interface JournalClient {
  getAttention(): Promise<GetAttentionResult>;
  getOperations(plantId: PlantId, window: OperationWindow): Promise<GetOperationsResult>;
  logOperation(plantId: PlantId, details: OperationDetails): Promise<LogOperationResult>;
  editOperation(operationId: OperationId, details: OperationDetails): Promise<EditOperationResult>;
  getSubstrateComponents(): Promise<CatalogReadResult<SubstrateComponent>>;
  addSubstrateComponent(
    data: SubstrateComponentData,
  ): Promise<CatalogAddResult<SubstrateComponent>>;
  editSubstrateComponent(
    id: SubstrateComponentId,
    data: SubstrateComponentData,
  ): Promise<CatalogEditResult<SubstrateComponent>>;
  getPesticides(): Promise<CatalogReadResult<Pesticide>>;
  addPesticide(data: PesticideData): Promise<CatalogAddResult<Pesticide>>;
  editPesticide(id: PesticideId, data: PesticideData): Promise<CatalogEditResult<Pesticide>>;
}
