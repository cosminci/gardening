import type * as Journal from "../domain/Journal";

const timeUntilWatering = (watering: Journal.WateringAttention): bigint | undefined =>
  watering.kind === "unavailable" ? undefined : watering.averageInterval - watering.elapsed;

const comparePlantAttention = (first: Journal.PlantAttention, second: Journal.PlantAttention) => {
  const firstTime = timeUntilWatering(first.watering);
  const secondTime = timeUntilWatering(second.watering);
  if (firstTime === undefined) return -Number(secondTime !== undefined);
  if (secondTime === undefined) return 1;
  return Number(firstTime > secondTime) - Number(firstTime < secondTime);
};

export const orderPlantAttention = (plants: readonly Journal.PlantAttention[]) =>
  plants.toSorted(comparePlantAttention);
