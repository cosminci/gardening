import { describe, expect, it } from "vitest";
import { makeHttpPlantAttentionClient } from "../../../src/adapters/http/HttpPlantAttentionClient";
import * as Journal from "../../../src/domain/Journal";
import { jsonResponse, respondingWith } from "./HttpTestSupport";

describe("HttpPlantAttentionClient", () => {
  it("should translate the attention projection into domain values", async () => {
    const noSamples = Journal.plantId("no-samples");
    const unknown = Journal.plantId("unknown");
    const finite = Journal.plantId("finite");
    const unbounded = Journal.plantId("unbounded");
    const current = Journal.plantId("current");
    const redAlert = Journal.plantId("red-alert");
    const requests: Request[] = [];
    const attention = makeHttpPlantAttentionClient(
      respondingWith(
        [
          jsonResponse({
            measuredAt: "2026-01-10T00:00:00Z",
            plants: [
              {
                plantId: noSamples,
                watering: { kind: "unavailable", sampleCount: 0, elapsedMillis: null },
              },
              {
                plantId: unknown,
                watering: { kind: "unavailable", sampleCount: 4, elapsedMillis: "86400000" },
              },
              {
                plantId: finite,
                watering: {
                  kind: "overdue",
                  sampleCount: 5,
                  averageIntervalMillis: "86400000",
                  elapsedMillis: "90000000",
                },
              },
              {
                plantId: unbounded,
                watering: {
                  kind: "overdue",
                  sampleCount: 5,
                  averageIntervalMillis: "0",
                  elapsedMillis: "1000",
                },
              },
              {
                plantId: current,
                watering: {
                  kind: "current",
                  sampleCount: 5,
                  averageIntervalMillis: "86400000",
                  elapsedMillis: "43200000",
                },
              },
              {
                plantId: redAlert,
                watering: {
                  kind: "redAlert",
                  sampleCount: 5,
                  averageIntervalMillis: "86400000",
                  elapsedMillis: "176400000",
                },
              },
            ],
          }),
        ],
        requests,
      ),
    );

    const result = await attention.getAttention();
    const expectedResult = {
      kind: "read",
      projection: {
        measuredAt: Journal.instant("2026-01-10T00:00:00Z"),
        plants: [
          {
            plantId: noSamples,
            watering: {
              kind: "unavailable",
              sampleCount: 0,
              maybeElapsed: null,
            },
          },
          {
            plantId: unknown,
            watering: {
              kind: "unavailable",
              sampleCount: 4,
              maybeElapsed: Journal.milliseconds("86400000"),
            },
          },
          {
            plantId: finite,
            watering: {
              kind: "overdue",
              sampleCount: 5,
              averageInterval: Journal.milliseconds("86400000"),
              elapsed: Journal.milliseconds("90000000"),
            },
          },
          {
            plantId: unbounded,
            watering: {
              kind: "overdue",
              sampleCount: 5,
              averageInterval: Journal.milliseconds("0"),
              elapsed: Journal.milliseconds("1000"),
            },
          },
          {
            plantId: current,
            watering: {
              kind: "current",
              sampleCount: 5,
              averageInterval: Journal.milliseconds("86400000"),
              elapsed: Journal.milliseconds("43200000"),
            },
          },
          {
            plantId: redAlert,
            watering: {
              kind: "redAlert",
              sampleCount: 5,
              averageInterval: Journal.milliseconds("86400000"),
              elapsed: Journal.milliseconds("176400000"),
            },
          },
        ],
      },
    };

    expect(result).toEqual(expectedResult);
    expect(new URL(requests.at(0)?.url ?? "").pathname).toBe("/attention");
  });

  it("should reject malformed watering-attention measurements", async () => {
    const attention = makeHttpPlantAttentionClient(
      respondingWith([
        jsonResponse({
          measuredAt: "2026-01-10T00:00:00Z",
          plants: [
            {
              plantId: Journal.plantId("p1"),
              watering: {
                kind: "overdue",
                sampleCount: 5,
                averageIntervalMillis: "86400000",
                elapsedMillis: "soon",
              },
            },
          ],
        }),
      ]),
    );

    const result = await attention.getAttention();
    const expectedResult = {
      kind: "readFailed",
      reason: new RangeError("invalid milliseconds: soon"),
    };

    expect(result).toEqual(expectedResult);
  });

  it("should reject an unknown watering-attention classification", async () => {
    const attention = makeHttpPlantAttentionClient(
      respondingWith([
        jsonResponse({
          measuredAt: "2026-01-10T00:00:00Z",
          plants: [
            {
              plantId: Journal.plantId("p1"),
              watering: {
                kind: "futureState",
                sampleCount: 5,
                averageIntervalMillis: "86400000",
                elapsedMillis: "90000000",
              },
            },
          ],
        }),
      ]),
    );

    const result = await attention.getAttention();
    const expectedResult = {
      kind: "readFailed",
      reason: new Error(
        'invalid watering attention: {"kind":"futureState","sampleCount":5,"averageIntervalMillis":"86400000","elapsedMillis":"90000000"}',
      ),
    };

    expect(result).toEqual(expectedResult);
  });

  it("should report HTTP and network failures as attention read failures", async () => {
    const reason = new Error("offline");
    const httpAttention = makeHttpPlantAttentionClient(
      respondingWith([jsonResponse({ message: "attention unavailable" }, 503)]),
    );
    const networkAttention = makeHttpPlantAttentionClient(respondingWith([reason]));

    const httpResult = await httpAttention.getAttention();
    const networkResult = await networkAttention.getAttention();
    const expectedHttpResult = {
      kind: "readFailed",
      reason: new Error("journal request failed"),
    };

    expect(httpResult).toEqual(expectedHttpResult);
    expect(networkResult).toEqual({ kind: "readFailed", reason });
  });
});
