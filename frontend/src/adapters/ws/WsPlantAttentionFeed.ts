import type { components } from "@contract";
import * as Journal from "../../domain/Journal";
import type { PlantAttentionFeed } from "../../domain/PlantAttention";

type Wire = components["schemas"];

export const makeWsPlantAttentionFeed = (
  wsFactory: (url: string) => WebSocket,
): PlantAttentionFeed => ({
  subscribe(listener) {
    let stopped = false;
    let socket: WebSocket;

    const connect = () => {
      const proto = location.protocol === "https:" ? "wss:" : "ws:";
      socket = wsFactory(`${proto}//${location.host}/attention/feed`);
      listener({ kind: "connectionState", state: "connecting" });

      socket.onopen = () => {
        listener({ kind: "connectionState", state: "connected" });
      };

      socket.onmessage = ({ data }) => {
        try {
          listener({
            kind: "projection",
            projection: toAttentionProjection(
              JSON.parse(data as string) as Wire["AttentionProjection"],
            ),
          });
        } catch {
          // ignore malformed messages
        }
      };

      socket.onerror = () => {
        // onclose fires next and handles the disconnect
      };

      socket.onclose = () => {
        if (!stopped) {
          listener({ kind: "connectionState", state: "disconnected" });
          setTimeout(connect, 5_000);
        }
      };
    };

    connect();
    return () => {
      stopped = true;
      socket.close();
    };
  },
});

const toAttentionProjection = (
  value: Wire["AttentionProjection"],
): Journal.AttentionProjection => ({
  measuredAt: Journal.instant(value.measuredAt),
  plants: value.plants.map(toAttentionSample),
});

const toAttentionSample = (value: Wire["PlantAttention"]): Journal.AttentionSample => {
  const watering = value.watering;
  switch (watering.kind) {
    case "unavailable":
      return {
        plant: Journal.plantId(value.plantId),
        watering: {
          kind: "unavailable",
          sampleCount: watering.sampleCount,
          maybeElapsed:
            watering.elapsedMillis === null ? null : Journal.milliseconds(watering.elapsedMillis),
        },
      };
    case "current":
    case "overdue":
    case "redAlert":
      return {
        plant: Journal.plantId(value.plantId),
        watering: {
          kind: watering.kind,
          sampleCount: watering.sampleCount,
          averageInterval: Journal.milliseconds(watering.averageIntervalMillis),
          elapsed: Journal.milliseconds(watering.elapsedMillis),
        },
      };
    default:
      throw new Error(`invalid watering attention: ${JSON.stringify(watering)}`);
  }
};
