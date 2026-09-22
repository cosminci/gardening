import { describe, expect, it } from "vitest";
import { orderPlantAttention } from "../../src/app/PlantAttentionOrdering";
import {
  location,
  nickname,
  plantId,
  species,
  type Plant,
  type PlantAttention,
  type Urgency,
} from "../../src/domain/Journal";
import * as JournalFixtures from "./JournalTestSupport";

describe("plant attention ordering", () => {
  const plant = (
    id: string,
    plantLocation = "Office",
    plantSpecies = "Ficus",
    plantNickname: string | null = null,
  ): Plant => ({
    ...JournalFixtures.ficus(),
    id: plantId(id),
    details: {
      ...JournalFixtures.ficus().details,
      location: location(plantLocation),
      species: species(plantSpecies),
      maybeNickname: plantNickname === null ? null : nickname(plantNickname),
    },
  });

  const inferred = (value: Plant, urgency: Urgency): PlantAttention =>
    JournalFixtures.inferredAttention(value, "current", urgency);

  const finite = (numeratorNanos: string, denominatorNanos: string): Urgency => ({
    kind: "finite",
    numeratorNanos,
    denominatorNanos,
  });

  const ids = (values: readonly PlantAttention[]) =>
    orderPlantAttention(values).map(({ plant: value }) => value.id);

  it("should order cadence and exact urgency in both input directions", () => {
    const unknown = JournalFixtures.unavailableAttention(plant("unknown"));
    const unbounded = inferred(plant("unbounded"), { kind: "unbounded" });
    const high = inferred(plant("high"), finite("3", "2"));
    const equal = inferred(plant("equal"), finite("6", "4"));
    const low = inferred(plant("low"), finite("1", "2"));
    const zero = inferred(plant("zero"), finite("0", "0"));

    expect(ids([high, unknown])).toEqual(["unknown", "high"]);
    expect(ids([unknown, high])).toEqual(["unknown", "high"]);
    expect(ids([high, unbounded])).toEqual(["unbounded", "high"]);
    expect(ids([unbounded, high])).toEqual(["unbounded", "high"]);
    expect(ids([unbounded, unbounded])).toEqual(["unbounded", "unbounded"]);
    expect(ids([low, high])).toEqual(["high", "low"]);
    expect(ids([high, low])).toEqual(["high", "low"]);
    expect(ids([equal, high])).toEqual(["equal", "high"]);
    expect(ids([zero, low])).toEqual(["low", "zero"]);
    expect(ids([low, zero])).toEqual(["low", "zero"]);
  });

  it("should order ties by location, species, nickname, then identifier", () => {
    const beforeByLocation = JournalFixtures.unavailableAttention(plant("location-a", "Balcony"));
    const afterByLocation = JournalFixtures.unavailableAttention(plant("location-z", "Kitchen"));
    const beforeBySpecies = JournalFixtures.unavailableAttention(
      plant("species-a", "Office", "Ficus"),
    );
    const afterBySpecies = JournalFixtures.unavailableAttention(
      plant("species-z", "Office", "Monstera"),
    );
    const beforeByNickname = JournalFixtures.unavailableAttention(
      plant("nickname-a", "Office", "Monstera"),
    );
    const afterByNickname = JournalFixtures.unavailableAttention(
      plant("nickname-z", "Office", "Monstera", "Monty"),
    );
    const beforeById = JournalFixtures.unavailableAttention(
      plant("id-a", "Office", "Monstera", "Monty"),
    );
    const afterById = JournalFixtures.unavailableAttention(
      plant("id-z", "Office", "Monstera", "Monty"),
    );

    expect(ids([afterByLocation, beforeByLocation])).toEqual(["location-a", "location-z"]);
    expect(ids([beforeByLocation, afterByLocation])).toEqual(["location-a", "location-z"]);
    expect(ids([afterBySpecies, beforeBySpecies])).toEqual(["species-a", "species-z"]);
    expect(ids([beforeBySpecies, afterBySpecies])).toEqual(["species-a", "species-z"]);
    expect(ids([afterByNickname, beforeByNickname])).toEqual(["nickname-a", "nickname-z"]);
    expect(ids([beforeByNickname, afterByNickname])).toEqual(["nickname-a", "nickname-z"]);
    expect(ids([afterById, beforeById])).toEqual(["id-a", "id-z"]);
    expect(ids([beforeById, afterById])).toEqual(["id-a", "id-z"]);
    expect(ids([beforeById, beforeById])).toEqual(["id-a", "id-a"]);
  });
});
