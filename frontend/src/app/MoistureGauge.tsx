import { Show, createUniqueId } from "solid-js";
import type { Component } from "solid-js";
import type * as Journal from "../domain/Journal";
import { moistureLabels } from "./JournalLabels";

const dropPath = "M12 3C12 3 4.5 12.5 4.5 15.5a7.5 7.5 0 0 0 15 0C19.5 12.5 12 3 12 3Z";
const dropTop = 3;
const dropBottom = 23;

const fillPercent: Record<Journal.MoistureLevel, number> = {
  wet: 100,
  moderatePlus: 67,
  moderateMinus: 33,
  dry: 0,
  noReading: 0,
};

export const MoistureGauge: Component<{ level: Journal.MoistureLevel }> = (props) => {
  const clipId = createUniqueId();
  const unknown = () => props.level === "noReading";
  const fillHeight = () => (fillPercent[props.level] / 100) * (dropBottom - dropTop);

  return (
    <svg
      class="moisture-gauge"
      classList={{ "moisture-gauge--unknown": unknown() }}
      viewBox="0 0 24 24"
      role="img"
      aria-label={moistureLabels[props.level]}
    >
      <path class="moisture-gauge__outline" d={dropPath} />
      <Show when={!unknown()}>
        <clipPath id={clipId}>
          <path d={dropPath} />
        </clipPath>
        <rect
          class="moisture-gauge__fill"
          clip-path={`url(#${clipId})`}
          x="0"
          y={dropBottom - fillHeight()}
          width="24"
          height={fillHeight()}
        />
      </Show>
    </svg>
  );
};
