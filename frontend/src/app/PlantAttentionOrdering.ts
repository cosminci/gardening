import type * as Journal from "../domain/Journal";

const compareText = (first: string, second: string) =>
  Number(first > second) - Number(first < second);

type AttentionScore =
  | { readonly kind: "unbounded" }
  | { readonly kind: "ratio"; readonly numerator: bigint; readonly denominator: bigint };

const attentionScore = (watering: Journal.WateringAttention): AttentionScore | null => {
  if (watering.kind === "unavailable") return null;
  const zeroAverage = watering.averageInterval === 0n;
  if (zeroAverage && watering.elapsed > 0n) return { kind: "unbounded" };
  return {
    kind: "ratio",
    numerator: watering.elapsed,
    denominator: zeroAverage ? 1n : watering.averageInterval,
  };
};

const compareAttentionScores = (first: AttentionScore, second: AttentionScore) => {
  if (first.kind === "unbounded" || second.kind === "unbounded")
    return Number(second.kind === "unbounded") - Number(first.kind === "unbounded");

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
