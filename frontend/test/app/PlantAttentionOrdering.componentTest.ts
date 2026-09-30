import { describe, expect, it } from "vitest";
import { orderPlantAttention } from "../../src/app/PlantAttentionOrdering";
import * as Journal from "../../src/domain/Journal";

describe("plant attention ordering", () => {
  const referencePlant: Journal.Plant = {
    id: Journal.plantId("plant"),
    details: {
      species: Journal.species("Ficus"),
      maybeNickname: null,
      location: Journal.location("Office"),
      substrate: Journal.substrate([]),
      status: "active",
    },
  };

  it("should place plants without a watering deadline before plants with one", () => {
    const unknownPlant = { ...referencePlant, id: Journal.plantId("unknown") };
    const scoredPlant = { ...referencePlant, id: Journal.plantId("scored") };
    const unknown: Journal.PlantAttention = {
      plant: unknownPlant,
      watering: { kind: "unavailable", sampleCount: 4, maybeElapsed: null },
    };
    const scored: Journal.PlantAttention = {
      plant: scoredPlant,
      watering: {
        kind: "current",
        sampleCount: 5,
        averageInterval: Journal.milliseconds("172800000"),
        elapsed: Journal.milliseconds("86400000"),
      },
    };

    const orderedPlantIds = orderPlantAttention([scored, unknown]).map(({ plant }) => plant.id);

    expect(orderedPlantIds).toEqual(["unknown", "scored"]);
  });

  it("should order by time remaining regardless of cadence or attention category", () => {
    const lessUrgentPlant = { ...referencePlant, id: Journal.plantId("less-urgent") };
    const moreUrgentPlant = { ...referencePlant, id: Journal.plantId("more-urgent") };
    const overduePlant = { ...referencePlant, id: Journal.plantId("overdue") };
    const lessUrgent: Journal.PlantAttention = {
      plant: lessUrgentPlant,
      watering: {
        kind: "current",
        sampleCount: 5,
        averageInterval: Journal.milliseconds("864000000"),
        elapsed: Journal.milliseconds("604800000"),
      },
    };
    const moreUrgent: Journal.PlantAttention = {
      plant: moreUrgentPlant,
      watering: {
        kind: "current",
        sampleCount: 5,
        averageInterval: Journal.milliseconds("172800000"),
        elapsed: Journal.milliseconds("86400000"),
      },
    };
    const overdue: Journal.PlantAttention = {
      plant: overduePlant,
      watering: {
        kind: "redAlert",
        sampleCount: 5,
        averageInterval: Journal.milliseconds("86400000"),
        elapsed: Journal.milliseconds("259200000"),
      },
    };

    const orderedPlantIds = orderPlantAttention([lessUrgent, moreUrgent, overdue]).map(
      ({ plant }) => plant.id,
    );

    expect(orderedPlantIds).toEqual(["overdue", "more-urgent", "less-urgent"]);
  });

  it("should place a plant with elapsed time and no average interval above recently watered plants", () => {
    const elapsedPlant = { ...referencePlant, id: Journal.plantId("elapsed") };
    const recentlyWateredPlant = {
      ...referencePlant,
      id: Journal.plantId("recently-watered"),
    };
    const elapsed: Journal.PlantAttention = {
      plant: elapsedPlant,
      watering: {
        kind: "overdue",
        sampleCount: 5,
        averageInterval: Journal.milliseconds("0"),
        elapsed: Journal.milliseconds("1"),
      },
    };
    const recentlyWatered: Journal.PlantAttention = {
      plant: recentlyWateredPlant,
      watering: {
        kind: "current",
        sampleCount: 5,
        averageInterval: Journal.milliseconds("0"),
        elapsed: Journal.milliseconds("0"),
      },
    };

    const orderedPlantIds = orderPlantAttention([recentlyWatered, elapsed]).map(
      ({ plant }) => plant.id,
    );

    expect(orderedPlantIds).toEqual(["elapsed", "recently-watered"]);
  });

  it("should preserve input order when plants have the same watering deadline", () => {
    const firstPlant = { ...referencePlant, id: Journal.plantId("first") };
    const secondPlant = { ...referencePlant, id: Journal.plantId("second") };
    const first: Journal.PlantAttention = {
      plant: firstPlant,
      watering: {
        kind: "overdue",
        sampleCount: 5,
        averageInterval: Journal.milliseconds("2"),
        elapsed: Journal.milliseconds("3"),
      },
    };
    const second: Journal.PlantAttention = {
      plant: secondPlant,
      watering: {
        kind: "overdue",
        sampleCount: 5,
        averageInterval: Journal.milliseconds("4"),
        elapsed: Journal.milliseconds("6"),
      },
    };

    const orderedPlantIds = orderPlantAttention([second, first]).map(({ plant }) => plant.id);

    expect(orderedPlantIds).toEqual(["second", "first"]);
  });

  it("should order all four watering kinds: unavailable first, then scored plants by urgency", () => {
    const unavailablePlant = { ...referencePlant, id: Journal.plantId("unavailable") };
    const currentPlant = { ...referencePlant, id: Journal.plantId("current") };
    const overduePlant = { ...referencePlant, id: Journal.plantId("overdue") };
    const redAlertPlant = { ...referencePlant, id: Journal.plantId("red-alert") };
    const unavailable: Journal.PlantAttention = {
      plant: unavailablePlant,
      watering: { kind: "unavailable", sampleCount: 0, maybeElapsed: null },
    };
    const current: Journal.PlantAttention = {
      plant: currentPlant,
      watering: {
        kind: "current",
        sampleCount: 5,
        averageInterval: Journal.milliseconds("86400000"),
        elapsed: Journal.milliseconds("43200000"),
      },
    };
    const overdue: Journal.PlantAttention = {
      plant: overduePlant,
      watering: {
        kind: "overdue",
        sampleCount: 5,
        averageInterval: Journal.milliseconds("86400000"),
        elapsed: Journal.milliseconds("90000000"),
      },
    };
    const redAlert: Journal.PlantAttention = {
      plant: redAlertPlant,
      watering: {
        kind: "redAlert",
        sampleCount: 5,
        averageInterval: Journal.milliseconds("86400000"),
        elapsed: Journal.milliseconds("172800000"),
      },
    };

    const orderedIds = orderPlantAttention([current, redAlert, unavailable, overdue]).map(
      ({ plant }) => plant.id,
    );

    expect(orderedIds).toEqual(["unavailable", "red-alert", "overdue", "current"]);
  });

  it("should preserve input order for plants without a watering deadline", () => {
    const watering: Journal.WateringAttention = {
      kind: "unavailable",
      sampleCount: 4,
      maybeElapsed: null,
    };
    const kitchenFicus = {
      ...referencePlant,
      id: Journal.plantId("kitchen-ficus"),
      details: { ...referencePlant.details, location: Journal.location("Kitchen") },
    };
    const officeFicus = {
      ...referencePlant,
      id: Journal.plantId("office-ficus"),
      details: { ...referencePlant.details, location: Journal.location("Office") },
    };
    const officeMonstera = {
      ...referencePlant,
      id: Journal.plantId("office-monstera"),
      details: {
        ...referencePlant.details,
        location: Journal.location("Office"),
        species: Journal.species("Monstera"),
      },
    };
    const officeMontyA = {
      ...referencePlant,
      id: Journal.plantId("office-monty-a"),
      details: {
        ...referencePlant.details,
        location: Journal.location("Office"),
        species: Journal.species("Monstera"),
        maybeNickname: Journal.nickname("Monty"),
      },
    };
    const officeMontyZ = { ...officeMontyA, id: Journal.plantId("office-monty-z") };
    const attention = [officeMontyZ, officeMontyA, officeMonstera, officeFicus, kitchenFicus].map(
      (plant) => ({ plant, watering }),
    );

    const orderedPlantIds = orderPlantAttention(attention).map(({ plant }) => plant.id);

    expect(orderedPlantIds).toEqual([
      "office-monty-z",
      "office-monty-a",
      "office-monstera",
      "office-ficus",
      "kitchen-ficus",
    ]);
  });
});
