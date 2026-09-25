import type * as Journal from "./Journal";

export type FeedConnectionState = "connecting" | "connected" | "disconnected";

export type FeedEvent =
  | { readonly kind: "projection"; readonly projection: Journal.AttentionProjection }
  | { readonly kind: "connectionState"; readonly state: FeedConnectionState };

export interface PlantAttentionFeed {
  subscribe(listener: (event: FeedEvent) => void): () => void;
}
