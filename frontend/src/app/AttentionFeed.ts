import { createMemo, createSignal, onCleanup, onMount } from "solid-js";
import type * as Journal from "../domain/Journal";
import type { FeedConnectionState, PlantAttentionFeed } from "../domain/PlantAttention";
import { isAttentionProjectionValid } from "../domain/PlantAttention";
import type { GardenHistory } from "./JournalList";
import { orderPlantAttention } from "./PlantAttentionOrdering";

export interface AttentionFeed {
  readonly orderedGardenHistories: () => readonly GardenHistory[];
  readonly attentionProjection: () => Journal.AttentionProjection | undefined;
  readonly connectionState: () => FeedConnectionState;
  readonly lastUpdateAt: () => number | undefined;
  // Plants archived locally that the projection may not reflect yet; exposed so
  // the journal load can treat their samples as expected rather than stale.
  readonly recentlyArchived: ReadonlySet<Journal.PlantId>;
  readonly markRecentlyArchived: (id: Journal.PlantId) => void;
}

// Owns the live attention overlay: the subscription lifecycle, the current
// projection and connection state, and the garden ordering derived from them.
// `activeIds` returns undefined until the journal has loaded its plant set, so
// projections arriving before then are accepted without a consistency check.
export const createAttentionFeed = (
  getFeed: () => PlantAttentionFeed,
  gardenHistories: () => readonly GardenHistory[],
  activeIds: () => ReadonlySet<Journal.PlantId> | undefined,
  onInvalidProjection: () => void,
): AttentionFeed => {
  const [attentionProjection, setAttentionProjection] = createSignal<Journal.AttentionProjection>();
  const [connectionState, setConnectionState] = createSignal<FeedConnectionState>("connecting");
  const [lastUpdateAt, setLastUpdateAt] = createSignal<number>();
  const recentlyArchived = new Set<Journal.PlantId>();

  const orderedGardenHistories = createMemo(() => {
    const proj = attentionProjection();
    const histories = gardenHistories();
    if (proj === undefined) return histories;
    const samplesById = new Map(proj.plants.map((s) => [s.plant, s]));
    const pending: GardenHistory[] = [];
    const knownHistories: GardenHistory[] = [];
    const plantAttentions: { plant: Journal.Plant; watering: Journal.WateringAttention }[] = [];
    for (const h of histories) {
      const sample = samplesById.get(h.plant.id);
      if (sample !== undefined) {
        knownHistories.push(h);
        plantAttentions.push({ plant: h.plant, watering: sample.watering });
      } else {
        pending.push(h);
      }
    }
    const ordered = orderPlantAttention(plantAttentions);
    const sortedKnown = knownHistories.toSorted(
      (a, b) =>
        ordered.findIndex((pa) => pa.plant.id === a.plant.id) -
        ordered.findIndex((pa) => pa.plant.id === b.plant.id),
    );
    return [...pending, ...sortedKnown];
  });

  onMount(() => {
    const unsubscribe = getFeed().subscribe((event) => {
      if (event.kind === "connectionState") {
        setConnectionState(event.state);
      } else {
        const proj = event.projection;
        for (const id of recentlyArchived)
          if (!proj.plants.some((s) => s.plant === id)) recentlyArchived.delete(id);
        const ids = activeIds();
        if (ids !== undefined && !isAttentionProjectionValid(proj, ids, recentlyArchived)) {
          onInvalidProjection();
          return;
        }
        setAttentionProjection(proj);
        setLastUpdateAt(Date.now());
      }
    });
    onCleanup(() => {
      unsubscribe();
    });
  });

  return {
    orderedGardenHistories,
    attentionProjection,
    connectionState,
    lastUpdateAt,
    recentlyArchived,
    markRecentlyArchived: (id) => {
      recentlyArchived.add(id);
    },
  };
};
