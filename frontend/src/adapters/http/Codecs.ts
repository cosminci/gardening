import type { components } from "@contract";
import * as Journal from "../../domain/Journal";

type WireSubstrate = components["schemas"]["SubstratePart"][];

export const fromWireSubstrate = (value: WireSubstrate): Journal.Substrate =>
  Journal.substrate(
    value.map((part) => ({
      component: Journal.substrateComponentId(part.componentId),
      share: Journal.percentage(part.share),
    })),
  );

export const toWireSubstrate = (value: Journal.Substrate): WireSubstrate =>
  value.map((part) => ({ componentId: part.component, share: part.share }));
