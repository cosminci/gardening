import type * as Journal from "./Journal";

export interface PlantAttentionClient {
  getAttention(): Promise<Journal.GetAttentionResult>;
}
