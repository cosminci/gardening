import type * as Journal from "../domain/Journal";
import type { CemeteryHistory, GardenHistory } from "./JournalList";

const matchesPlantSearch = (details: Journal.PlantDetails, query: string): boolean => {
  const trimmed = query.trim().toLowerCase();
  if (trimmed === "") return true;
  const fields: readonly (string | null)[] = [
    details.species,
    details.maybeNickname,
    details.location,
  ];
  return fields.some((field) => field?.toLowerCase().includes(trimmed) ?? false);
};

export const filterHistoriesBySearch = (
  histories: readonly (GardenHistory | CemeteryHistory)[],
  query: string,
): readonly (GardenHistory | CemeteryHistory)[] =>
  histories.filter((history) => matchesPlantSearch(history.plant.details, query));
