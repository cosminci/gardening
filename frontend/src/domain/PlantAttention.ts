import type * as Journal from "./Journal";

export type FeedConnectionState = "connecting" | "connected" | "disconnected";

export type FeedEvent =
  | { readonly kind: "projection"; readonly projection: Journal.AttentionProjection }
  | { readonly kind: "connectionState"; readonly state: FeedConnectionState };

export interface PlantAttentionFeed {
  subscribe(listener: (event: FeedEvent) => void): () => void;
}

// A projection is trustworthy only if it samples each plant once and every
// sampled plant is still active — except plants archived so recently that the
// projection could not yet reflect their removal.
export const isAttentionProjectionValid = (
  projection: Journal.AttentionProjection,
  activeIds: ReadonlySet<Journal.PlantId>,
  recentlyArchived: ReadonlySet<Journal.PlantId>,
): boolean => {
  const samples = projection.plants;
  const uniquePlants = new Set(samples.map((sample) => sample.plant));
  if (samples.length !== uniquePlants.size) return false;
  return samples.every(
    (sample) => recentlyArchived.has(sample.plant) || activeIds.has(sample.plant),
  );
};
