import type * as Journal from "../domain/Journal";

const compareText = (first: string, second: string) =>
  first < second ? -1 : first > second ? 1 : 0;

const compareUrgency = (first: Journal.Urgency, second: Journal.Urgency) => {
  if (first.kind === "unbounded") return second.kind === "unbounded" ? 0 : -1;
  if (second.kind === "unbounded") return 1;

  const ratio = (urgency: Extract<Journal.Urgency, { kind: "finite" }>) => {
    const denominator = BigInt(urgency.denominatorNanos);
    return denominator === 0n
      ? { numerator: 0n, denominator: 1n }
      : { numerator: BigInt(urgency.numeratorNanos), denominator };
  };
  const firstRatio = ratio(first);
  const secondRatio = ratio(second);
  const firstProduct = firstRatio.numerator * secondRatio.denominator;
  const secondProduct = secondRatio.numerator * firstRatio.denominator;
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
