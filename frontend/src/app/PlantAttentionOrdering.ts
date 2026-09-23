import type * as Journal from "../domain/Journal";

const compareText = (first: string, second: string) =>
  Number(first > second) - Number(first < second);

interface AttentionScore {
  readonly unbounded: boolean;
  readonly numerator: bigint;
  readonly denominator: bigint;
}

const attentionScore = (watering: Journal.WateringAttention): AttentionScore | null => {
  if (watering.kind === "unavailable") return null;
  const zeroAverage = watering.averageInterval === 0n;
  const unbounded = zeroAverage && watering.elapsed > 0n;
  return {
    unbounded,
    numerator: watering.elapsed,
    denominator: unbounded ? 0n : zeroAverage ? 1n : watering.averageInterval,
  };
};

const compareAttentionScores = (first: AttentionScore, second: AttentionScore) => {
  const unbounded = Number(second.unbounded) - Number(first.unbounded);
  if (unbounded !== 0) return unbounded;

  const firstProduct = first.numerator * second.denominator;
  const secondProduct = second.numerator * first.denominator;
  return Number(firstProduct < secondProduct) - Number(firstProduct > secondProduct);
};

const comparePlantAttention = (first: Journal.PlantAttention, second: Journal.PlantAttention) => {
  const firstScore = attentionScore(first.watering);
  const secondScore = attentionScore(second.watering);

  const availability = Number(firstScore !== null) - Number(secondScore !== null);
  if (availability !== 0) return availability;
  if (firstScore !== null && secondScore !== null) {
    const attention = compareAttentionScores(firstScore, secondScore);
    if (attention !== 0) return attention;
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
