type Brand<Value, Name extends string> = Value & { readonly __brand: Name };

export type PlantId = Brand<string, "PlantId">;
export type OperationId = Brand<string, "OperationId">;
export type Species = Brand<string, "Species">;
export type Nickname = Brand<string, "Nickname">;
export type Location = Brand<string, "Location">;
export type Note = Brand<string, "Note">;
export type Instant = Brand<string, "Instant">;
export type Percentage = Brand<number, "Percentage">;

export const plantId = (value: string): PlantId => value as PlantId;
export const operationId = (value: string): OperationId => value as OperationId;
export const species = (value: string): Species => value as Species;
export const nickname = (value: string): Nickname => value as Nickname;
export const location = (value: string): Location => value as Location;
export const note = (value: string): Note => value as Note;
export const instant = (value: string): Instant => value as Instant;
export const percentage = (value: number): Percentage => value as Percentage;

export const plantStatuses = ["active", "archived"] as const;
export type PlantStatus = (typeof plantStatuses)[number];

export const substrateComponents = [
  "kekkilaUniversal",
  "kekkilaEricaceous",
  "perlite",
  "pineBark",
  "sand3to5",
  "sand4to8",
  "leca",
] as const;
export type SubstrateComponent = (typeof substrateComponents)[number];

export const actionTypes = ["watered", "fertilized", "pesticide", "pruned", "noAction"] as const;
export type ActionType = (typeof actionTypes)[number];

export const moistureLevels = ["wet", "moderatePlus", "moderateMinus", "dry", "noReading"] as const;
export type MoistureLevel = (typeof moistureLevels)[number];

export interface SubstratePart {
  readonly component: SubstrateComponent;
  readonly share: Percentage;
}

export type Substrate = Brand<readonly SubstratePart[], "Substrate">;
export const substrate = (parts: readonly SubstratePart[]): Substrate => parts as Substrate;

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

export interface CareOperationDetails {
  readonly kind: "care";
  readonly actions: ReadonlySet<ActionType>;
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

export type JournalRecord =
  | { readonly kind: "plant"; readonly id: PlantId }
  | { readonly kind: "operation"; readonly id: OperationId };
export interface JournalCorruption {
  readonly record: JournalRecord;
  readonly reason: Error;
}

export type GetPlantsResult =
  | { readonly kind: "read"; readonly plants: readonly Plant[] }
  | {
      readonly kind: "corrupted";
      readonly details: readonly [JournalCorruption, ...JournalCorruption[]];
    }
  | { readonly kind: "readFailed"; readonly reason: Error };

export type GetOperationsResult =
  | { readonly kind: "read"; readonly operations: readonly Operation[] }
  | {
      readonly kind: "corrupted";
      readonly details: readonly [JournalCorruption, ...JournalCorruption[]];
    }
  | { readonly kind: "readFailed"; readonly reason: Error };

export type LogOperationResult =
  | { readonly kind: "logged"; readonly id: OperationId }
  | { readonly kind: "loggingFailed"; readonly reason: Error };

export type EditOperationResult =
  | { readonly kind: "edited"; readonly operation: Operation }
  | { readonly kind: "operationMissing" }
  | { readonly kind: "operationTypeMismatch" }
  | {
      readonly kind: "corrupted";
      readonly details: readonly [JournalCorruption, ...JournalCorruption[]];
    }
  | { readonly kind: "editFailed"; readonly reason: Error };

export interface JournalClient {
  getPlants(): Promise<GetPlantsResult>;
  getOperations(plantId: PlantId): Promise<GetOperationsResult>;
  logOperation(plantId: PlantId, details: OperationDetails): Promise<LogOperationResult>;
  editOperation(operationId: OperationId, details: OperationDetails): Promise<EditOperationResult>;
}
