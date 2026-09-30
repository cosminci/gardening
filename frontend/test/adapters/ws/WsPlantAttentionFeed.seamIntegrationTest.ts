import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { makeWsPlantAttentionFeed } from "../../../src/adapters/ws/WsPlantAttentionFeed";
import * as Journal from "../../../src/domain/Journal";
import type { FeedEvent } from "../../../src/domain/PlantAttention";

const makeSocket = (url: string) => ({
  url,
  onopen: null as ((e: Event) => void) | null,
  onmessage: null as ((e: MessageEvent) => void) | null,
  onerror: null as ((e: Event) => void) | null,
  onclose: null as ((e: CloseEvent) => void) | null,
  close: vi.fn<() => void>(),
});

type FakeSocket = ReturnType<typeof makeSocket>;

const makeFakeWsFactory = () => {
  const sockets: FakeSocket[] = [];
  const factory = (url: string) => {
    const socket = makeSocket(url);
    sockets.push(socket);
    return socket as unknown as WebSocket;
  };
  return { factory, sockets };
};

const sampleProjection: Journal.AttentionProjection = {
  measuredAt: Journal.instant("2026-01-10T00:00:00Z"),
  plants: [
    {
      plant: Journal.plantId("p1"),
      watering: {
        kind: "current",
        sampleCount: 5,
        averageInterval: Journal.milliseconds("86400000"),
        elapsed: Journal.milliseconds("43200000"),
      },
    },
  ],
};

const wireProjection = JSON.stringify({
  measuredAt: "2026-01-10T00:00:00Z",
  plants: [
    {
      plantId: "p1",
      watering: {
        kind: "current",
        sampleCount: 5,
        averageIntervalMillis: "86400000",
        elapsedMillis: "43200000",
      },
    },
  ],
});

describe("WsPlantAttentionFeed", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it("should connect to the WebSocket feed URL derived from the current location", () => {
    const { factory, sockets } = makeFakeWsFactory();
    const feed = makeWsPlantAttentionFeed(factory);
    const unsubscribe = feed.subscribe(() => undefined);

    unsubscribe();
    expect(sockets[0]?.url).toBe(`ws://${location.host}/attention/feed`);
  });

  it("should use wss protocol when the page is served over https", () => {
    const host = location.host;
    vi.stubGlobal("location", { protocol: "https:", host });
    const { factory, sockets } = makeFakeWsFactory();
    const feed = makeWsPlantAttentionFeed(factory);
    const unsubscribe = feed.subscribe(() => undefined);
    unsubscribe();

    expect(sockets[0]?.url).toBe(`wss://${host}/attention/feed`);
  });

  it("should emit connecting state before the socket opens", () => {
    const { factory } = makeFakeWsFactory();
    const feed = makeWsPlantAttentionFeed(factory);
    const events: FeedEvent[] = [];

    const unsubscribe = feed.subscribe((event) => events.push(event));

    expect(events).toEqual([{ kind: "connectionState", state: "connecting" }]);
    unsubscribe();
  });

  it("should emit connected state when the socket opens", () => {
    const { factory, sockets } = makeFakeWsFactory();
    const feed = makeWsPlantAttentionFeed(factory);
    const events: FeedEvent[] = [];

    const unsubscribe = feed.subscribe((event) => events.push(event));
    sockets[0]?.onopen?.(new Event("open"));

    expect(events).toEqual([
      { kind: "connectionState", state: "connecting" },
      { kind: "connectionState", state: "connected" },
    ]);
    unsubscribe();
  });

  it("should decode and emit an attention projection on message", () => {
    const { factory, sockets } = makeFakeWsFactory();
    const feed = makeWsPlantAttentionFeed(factory);
    const events: FeedEvent[] = [];

    const unsubscribe = feed.subscribe((event) => events.push(event));
    sockets[0]?.onopen?.(new Event("open"));
    sockets[0]?.onmessage?.(new MessageEvent("message", { data: wireProjection }));

    expect(events).toEqual([
      { kind: "connectionState", state: "connecting" },
      { kind: "connectionState", state: "connected" },
      { kind: "projection", projection: sampleProjection },
    ]);
    unsubscribe();
  });

  it("should silently ignore malformed message data", () => {
    const { factory, sockets } = makeFakeWsFactory();
    const feed = makeWsPlantAttentionFeed(factory);
    const events: FeedEvent[] = [];

    const unsubscribe = feed.subscribe((event) => events.push(event));
    sockets[0]?.onmessage?.(new MessageEvent("message", { data: "not json" }));

    expect(events).toEqual([{ kind: "connectionState", state: "connecting" }]);
    unsubscribe();
  });

  it("should handle the onerror callback without emitting an event", () => {
    const { factory, sockets } = makeFakeWsFactory();
    const feed = makeWsPlantAttentionFeed(factory);
    const events: FeedEvent[] = [];

    const unsubscribe = feed.subscribe((event) => events.push(event));
    sockets[0]?.onerror?.(new Event("error"));

    expect(events).toEqual([{ kind: "connectionState", state: "connecting" }]);
    unsubscribe();
  });

  it("should emit disconnected and schedule reconnect when the socket closes", () => {
    const { factory, sockets } = makeFakeWsFactory();
    const feed = makeWsPlantAttentionFeed(factory);
    const events: FeedEvent[] = [];

    const unsubscribe = feed.subscribe((event) => events.push(event));
    sockets[0]?.onopen?.(new Event("open"));
    sockets[0]?.onclose?.(new CloseEvent("close"));

    expect(events).toEqual([
      { kind: "connectionState", state: "connecting" },
      { kind: "connectionState", state: "connected" },
      { kind: "connectionState", state: "disconnected" },
    ]);
    expect(sockets).toHaveLength(1);

    vi.advanceTimersByTime(5_000);

    expect(sockets).toHaveLength(2);
    expect(sockets[1]?.url).toBe(`ws://${location.host}/attention/feed`);
    expect(events.at(-1)).toEqual({ kind: "connectionState", state: "connecting" });
    unsubscribe();
  });

  it("should not reconnect after unsubscribing", () => {
    const { factory, sockets } = makeFakeWsFactory();
    const feed = makeWsPlantAttentionFeed(factory);

    const unsubscribe = feed.subscribe(() => undefined);
    sockets[0]?.onopen?.(new Event("open"));
    unsubscribe();
    sockets[0]?.onclose?.(new CloseEvent("close"));

    vi.advanceTimersByTime(5_000);

    expect(sockets).toHaveLength(1);
    expect(sockets[0]?.close).toHaveBeenCalledOnce();
  });

  it("should not reconnect when unsubscribed after a disconnect scheduled a reconnect", () => {
    const { factory, sockets } = makeFakeWsFactory();
    const feed = makeWsPlantAttentionFeed(factory);

    const unsubscribe = feed.subscribe(() => undefined);
    sockets[0]?.onopen?.(new Event("open"));
    sockets[0]?.onclose?.(new CloseEvent("close"));
    unsubscribe();

    vi.advanceTimersByTime(5_000);

    expect(sockets).toHaveLength(1);
  });

  it("should ignore open and message events that fire after unsubscribing", () => {
    const { factory, sockets } = makeFakeWsFactory();
    const feed = makeWsPlantAttentionFeed(factory);
    const events: FeedEvent[] = [];

    const unsubscribe = feed.subscribe((event) => events.push(event));
    sockets[0]?.onopen?.(new Event("open"));

    expect(events).toEqual([
      { kind: "connectionState", state: "connecting" },
      { kind: "connectionState", state: "connected" },
    ]);

    unsubscribe();
    sockets[0]?.onopen?.(new Event("open"));
    sockets[0]?.onmessage?.(new MessageEvent("message", { data: wireProjection }));

    expect(events).toEqual([
      { kind: "connectionState", state: "connecting" },
      { kind: "connectionState", state: "connected" },
    ]);
  });

  it("should decode unavailable watering with null and non-null elapsed", () => {
    const { factory, sockets } = makeFakeWsFactory();
    const feed = makeWsPlantAttentionFeed(factory);
    const events: FeedEvent[] = [];
    const wireMsg = JSON.stringify({
      measuredAt: "2026-01-10T00:00:00Z",
      plants: [
        { plantId: "p1", watering: { kind: "unavailable", sampleCount: 3, elapsedMillis: null } },
        {
          plantId: "p2",
          watering: { kind: "unavailable", sampleCount: 4, elapsedMillis: "86400000" },
        },
      ],
    });

    const unsubscribe = feed.subscribe((event) => events.push(event));
    sockets[0]?.onopen?.(new Event("open"));
    sockets[0]?.onmessage?.(new MessageEvent("message", { data: wireMsg }));

    const expected: Journal.AttentionProjection = {
      measuredAt: Journal.instant("2026-01-10T00:00:00Z"),
      plants: [
        {
          plant: Journal.plantId("p1"),
          watering: { kind: "unavailable", sampleCount: 3, maybeElapsed: null },
        },
        {
          plant: Journal.plantId("p2"),
          watering: {
            kind: "unavailable",
            sampleCount: 4,
            maybeElapsed: Journal.milliseconds("86400000"),
          },
        },
      ],
    };
    expect(events.at(-1)).toEqual({ kind: "projection", projection: expected });
    unsubscribe();
  });

  it("should silently ignore a message with an unrecognized watering kind", () => {
    const { factory, sockets } = makeFakeWsFactory();
    const feed = makeWsPlantAttentionFeed(factory);
    const events: FeedEvent[] = [];
    const wireMsg = JSON.stringify({
      measuredAt: "2026-01-10T00:00:00Z",
      plants: [{ plantId: "p1", watering: { kind: "supersonic", sampleCount: 1 } }],
    });

    const unsubscribe = feed.subscribe((event) => events.push(event));
    sockets[0]?.onmessage?.(new MessageEvent("message", { data: wireMsg }));

    expect(events).toEqual([{ kind: "connectionState", state: "connecting" }]);
    unsubscribe();
  });

  it("should silently ignore a message with an invalid milliseconds value in watering", () => {
    const { factory, sockets } = makeFakeWsFactory();
    const feed = makeWsPlantAttentionFeed(factory);
    const events: FeedEvent[] = [];
    const wireMsg = JSON.stringify({
      measuredAt: "2026-01-10T00:00:00Z",
      plants: [
        {
          plantId: "p1",
          watering: { kind: "unavailable", sampleCount: 1, elapsedMillis: "not-a-number" },
        },
      ],
    });

    const unsubscribe = feed.subscribe((event) => events.push(event));
    sockets[0]?.onmessage?.(new MessageEvent("message", { data: wireMsg }));

    expect(events).toEqual([{ kind: "connectionState", state: "connecting" }]);
    unsubscribe();
  });
});
