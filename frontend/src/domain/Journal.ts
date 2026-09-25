type Brand<Value, Name extends string> = Value & { readonly __brand: Name };

export type PlantId = Brand<string, "PlantId">;
export type OperationId = Brand<string, "OperationId">;
export type Species = Brand<string, "Species">;
export type Nickname = Brand<string, "Nickname">;
export type Location = Brand<string, "Location">;
export type Note = Brand<string, "Note">;
export type Instant = Brand<string, "Instant">;
export type Milliseconds = Brand<bigint, "Milliseconds">;
export type Percentage = Brand<number, "Percentage">;
export type SubstrateComponentId = Brand<string, "SubstrateComponentId">;
export type PesticideId = Brand<string, "PesticideId">;
export type SubstrateMixId = Brand<string, "SubstrateMixId">;
export type SubstrateComponentName = Brand<string, "SubstrateComponentName">;
export type SubstrateComponentInfo = Brand<string, "SubstrateComponentInfo">;
export type PesticideName = Brand<string, "PesticideName">;
export type PesticideInfo = Brand<string, "PesticideInfo">;
export type SubstrateMixName = Brand<string, "SubstrateMixName">;
export type SubstrateMixNotes = Brand<string, "SubstrateMixNotes">;

export const plantId = (value: string): PlantId => value as PlantId;
export const operationId = (value: string): OperationId => value as OperationId;
export const species = (value: string): Species => value as Species;
export const nickname = (value: string): Nickname => value as Nickname;
export const location = (value: string): Location => value as Location;
export const note = (value: string): Note => value as Note;
export const instant = (value: string): Instant => value as Instant;
export const milliseconds = (value: string): Milliseconds => {
  if (!/^[0-9]+$/.test(value)) throw new RangeError(`invalid milliseconds: ${value}`);
  return BigInt(value) as Milliseconds;
};
export const percentage = (value: number): Percentage => value as Percentage;
export const substrateComponentId = (value: string): SubstrateComponentId =>
  value as SubstrateComponentId;
export const pesticideId = (value: string): PesticideId => value as PesticideId;
export const substrateMixId = (value: string): SubstrateMixId => value as SubstrateMixId;
export const substrateComponentName = (value: string): SubstrateComponentName =>
  value as SubstrateComponentName;
export const substrateComponentInfo = (value: string): SubstrateComponentInfo =>
  value as SubstrateComponentInfo;
export const pesticideName = (value: string): PesticideName => value as PesticideName;
export const pesticideInfo = (value: string): PesticideInfo => value as PesticideInfo;
export const substrateMixName = (value: string): SubstrateMixName => value as SubstrateMixName;
export const substrateMixNotes = (value: string): SubstrateMixNotes => value as SubstrateMixNotes;

export const pesticideTypes = ["fungicide", "insecticide", "treatment"] as const;
export type PesticideType = (typeof pesticideTypes)[number];

export const pesticideStatuses = ["active", "archived"] as const;
export type PesticideStatus = (typeof pesticideStatuses)[number];

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
  readonly name: SubstrateComponentName;
  readonly maybeInfo: SubstrateComponentInfo | null;
}

export interface SubstrateComponent {
  readonly id: SubstrateComponentId;
  readonly data: SubstrateComponentData;
}

export interface PesticideData {
  readonly name: PesticideName;
  readonly pesticideType: PesticideType;
  readonly maybeInfo: PesticideInfo | null;
}

export interface Pesticide {
  readonly id: PesticideId;
  readonly data: PesticideData;
  readonly status: PesticideStatus;
}

export interface SubstrateMix {
  readonly id: SubstrateMixId;
  readonly name: SubstrateMixName;
  readonly maybeNotes: SubstrateMixNotes | null;
  readonly substrate: Substrate;
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

export type NewPlantDetails = Pick<
  PlantDetails,
  "species" | "maybeNickname" | "location" | "substrate"
>;

export type WateringAttention =
  | {
      readonly kind: "unavailable";
      readonly sampleCount: number;
      readonly maybeElapsed: Milliseconds | null;
    }
  | {
      readonly kind: "current";
      readonly sampleCount: number;
      readonly averageInterval: Milliseconds;
      readonly elapsed: Milliseconds;
    }
  | {
      readonly kind: "overdue";
      readonly sampleCount: number;
      readonly averageInterval: Milliseconds;
      readonly elapsed: Milliseconds;
    }
  | {
      readonly kind: "redAlert";
      readonly sampleCount: number;
      readonly averageInterval: Milliseconds;
      readonly elapsed: Milliseconds;
    };

export interface PlantAttention {
  readonly plant: Plant;
  readonly watering: WateringAttention;
}

export interface AttentionSample {
  readonly plantId: PlantId;
  readonly watering: WateringAttention;
}

export interface AttentionProjection {
  readonly measuredAt: Instant;
  readonly plants: readonly AttentionSample[];
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

export type OperationDates =
  | { readonly kind: "empty" }
  | { readonly kind: "recorded"; readonly first: Instant; readonly last: Instant };

export type GetAttentionResult =
  | { readonly kind: "read"; readonly projection: AttentionProjection }
  | { readonly kind: "readFailed"; readonly reason: Error };

export type GetPlantsResult =
  | { readonly kind: "read"; readonly plants: readonly Plant[] }
  | { readonly kind: "readFailed"; readonly reason: Error };

export type CreatePlantResult =
  | { readonly kind: "created"; readonly plant: Plant }
  | { readonly kind: "unknownComponent" }
  | { readonly kind: "catalogReadFailed"; readonly reason: Error }
  | { readonly kind: "createFailed"; readonly reason: Error };

export type GetArchivedCountResult =
  | { readonly kind: "read"; readonly count: number }
  | { readonly kind: "readFailed"; readonly reason: Error };

export type GetOperationDatesResult =
  | { readonly kind: "read"; readonly dates: OperationDates }
  | { readonly kind: "readFailed"; readonly reason: Error };

export type ArchivePlantResult =
  | { readonly kind: "archived" }
  | { readonly kind: "plantMissing" }
  | { readonly kind: "alreadyArchived" }
  | { readonly kind: "archiveFailed"; readonly reason: Error };

export type EditPlantResult =
  | { readonly kind: "edited" }
  | { readonly kind: "plantMissing" }
  | { readonly kind: "plantArchived" }
  | { readonly kind: "unknownComponent" }
  | { readonly kind: "catalogReadFailed"; readonly reason: Error }
  | { readonly kind: "editFailed"; readonly reason: Error };

export type GetOperationsResult =
  | { readonly kind: "read"; readonly page: OperationPage }
  | { readonly kind: "readFailed"; readonly reason: Error };

export type LogOperationResult =
  | { readonly kind: "logged"; readonly id: OperationId }
  | { readonly kind: "plantArchived" }
  | { readonly kind: "loggingFailed"; readonly reason: Error };

export type EditOperationResult =
  | { readonly kind: "edited"; readonly operation: Operation }
  | { readonly kind: "operationMissing" }
  | { readonly kind: "operationTypeMismatch" }
  | { readonly kind: "editFailed"; readonly reason: Error };

export type DeleteOperationResult =
  | { readonly kind: "deleted" }
  | { readonly kind: "operationMissing" }
  | { readonly kind: "cannotDeleteLatestRepot" }
  | { readonly kind: "deleteFailed"; readonly reason: Error };

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

export type PesticideEditResult =
  | { readonly kind: "edited"; readonly entry: Pesticide }
  | { readonly kind: "pesticideMissing" }
  | { readonly kind: "pesticideArchived" }
  | { readonly kind: "editFailed"; readonly reason: Error };

export type PesticideArchiveResult =
  | { readonly kind: "archived"; readonly entry: Pesticide }
  | { readonly kind: "pesticideMissing" }
  | { readonly kind: "alreadyArchived" }
  | { readonly kind: "archiveFailed"; readonly reason: Error };

export type CatalogDeleteResult =
  { readonly kind: "deleted" } | { readonly kind: "deleteFailed"; readonly reason: Error };

export type AddSubstrateMixResult =
  | { readonly kind: "added"; readonly entry: SubstrateMix }
  | { readonly kind: "duplicateSubstrate" }
  | { readonly kind: "addFailed"; readonly reason: Error };
