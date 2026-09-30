import { describe, expect, it } from "vitest";
import { filterHistoriesBySearch } from "../../src/app/PlantSearch";
import type { GardenHistory } from "../../src/app/JournalList";
import * as Journal from "../../src/domain/Journal";

describe("plant search filtering", () => {
  const gardenHistory = (details: Partial<Journal.PlantDetails>, id: string): GardenHistory => ({
    kind: "garden",
    plant: {
      id: Journal.plantId(id),
      details: {
        species: Journal.species("Ficus"),
        maybeNickname: null,
        location: Journal.location("Office"),
        substrate: Journal.substrate([]),
        status: "active",
        ...details,
      },
    },
    page: { operations: [], hasNextPage: false },
  });

  it("should show every plant for a blank or whitespace-only query", () => {
    const fern = gardenHistory({ species: Journal.species("Fern") }, "fern");
    const monstera = gardenHistory({ species: Journal.species("Monstera") }, "monstera");

    expect(filterHistoriesBySearch([fern, monstera], "")).toEqual([fern, monstera]);
    expect(filterHistoriesBySearch([fern, monstera], "   ")).toEqual([fern, monstera]);
  });

  it("should match a case-insensitive substring of the species, nickname, or location", () => {
    const bySpecies = gardenHistory({ species: Journal.species("Monstera Deliciosa") }, "species");
    const byNickname = gardenHistory({ maybeNickname: Journal.nickname("Monty") }, "nickname");
    const byLocation = gardenHistory({ location: Journal.location("Living Room") }, "location");
    const unrelated = gardenHistory({ species: Journal.species("Fern") }, "unrelated");

    const matched = filterHistoriesBySearch([bySpecies, byNickname, byLocation, unrelated], "mon");

    expect(matched.map((h) => h.plant.id)).toEqual(["species", "nickname"]);
  });

  it("should match a plant whose location contains the query", () => {
    const kitchenPlant = gardenHistory({ location: Journal.location("Kitchen") }, "kitchen");
    const officePlant = gardenHistory({ location: Journal.location("Office") }, "office");

    const matched = filterHistoriesBySearch([kitchenPlant, officePlant], "kitchen");

    expect(matched.map((h) => h.plant.id)).toEqual(["kitchen"]);
  });

  it("should not match a plant with no nickname against an empty field", () => {
    const noNickname = gardenHistory({ maybeNickname: null }, "no-nickname");

    expect(filterHistoriesBySearch([noNickname], "monty")).toEqual([]);
  });
});
