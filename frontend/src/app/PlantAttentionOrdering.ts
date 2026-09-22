import type * as Journal from "../domain/Journal";

const compareText = (first: string, second: string) =>
  first < second ? -1 : first > second ? 1 : 0;

const compareUrgency = (first: Journal.Urgency, second: Journal.Urgency) => {
  if (first.kind === "unbounded") return second.kind === "unbounded" ? 0 : -1;
  if (second.kind === "unbounded") return 1;

  const firstDenominator = BigInt(first.denominatorNanos);
  const secondDenominator = BigInt(second.denominatorNanos);
  const firstProduct =
    BigInt(first.numeratorNanos) * (secondDenominator === 0n ? 1n : secondDenominator);
  const secondProduct =
    BigInt(second.numeratorNanos) * (firstDenominator === 0n ? 1n : firstDenominator);
  return firstProduct > secondProduct ? -1 : firstProduct < secondProduct ? 1 : 0;
};

const comparePlantAttention = (first: Journal.PlantAttention, second: Journal.PlantAttention) => {
  if (first.cadence.kind === "unavailable" && second.cadence.kind === "inferred") return -1;
  if (first.cadence.kind === "inferred" && second.cadence.kind === "unavailable") return 1;
  if (first.cadence.kind === "inferred" && second.cadence.kind === "inferred") {
    const urgency = compareUrgency(first.cadence.urgency, second.cadence.urgency);
    if (urgency !== 0) return urgency;
  }

  const firstDetails = first.plant.details;
  const secondDetails = second.plant.details;
  return (
    compareText(firstDetails.location, secondDetails.location) ||
    compareText(firstDetails.species, secondDetails.species) ||
    compareText(firstDetails.maybeNickname ?? "", secondDetails.maybeNickname ?? "") ||
    compareText(first.plant.id, second.plant.id)
  );
};

export const orderPlantAttention = (plants: readonly Journal.PlantAttention[]) =>
  plants.toSorted(comparePlantAttention);
