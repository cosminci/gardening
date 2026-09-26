import { Index, Show } from "solid-js";
import type { Component } from "solid-js";
import type * as Journal from "../domain/Journal";
import type { OperationHistoryChange } from "./OperationHistory";
import { PlantCard } from "./PlantCard";

export interface GardenHistory {
  readonly kind: "garden";
  readonly plant: Journal.Plant;
  readonly page: Journal.OperationPage;
}

export interface CemeteryHistory {
  readonly kind: "cemetery";
  readonly plant: Journal.Plant;
  readonly dates: Journal.OperationDates;
  readonly page: Journal.OperationPage;
}

interface JournalListProps {
  readonly view: "garden" | "cemetery";
  readonly histories: readonly (GardenHistory | CemeteryHistory)[];
  readonly emptyMessage: string | undefined;
  readonly attentionProjection: Journal.AttentionProjection | undefined;
  readonly substrateComponents: readonly Journal.SubstrateComponent[];
  readonly pesticides: readonly Journal.Pesticide[];
  readonly operationChange: OperationHistoryChange | undefined;
  readonly getOperations: (
    plant: Journal.PlantId,
    window: Journal.OperationWindow,
  ) => Promise<Journal.GetOperationsResult>;
  readonly onViewPhotos: (plant: Journal.Plant) => void;
  readonly onLog: (plant: Journal.PlantId) => void;
  readonly onArchive: (plant: Journal.Plant) => void;
  readonly onEditPlant: (plant: Journal.Plant) => void;
  readonly onEdit: (operation: Journal.Operation) => void;
}

export const JournalList: Component<JournalListProps> = (props) => (
  <section class="journal" aria-label={props.view === "garden" ? "Garden" : "Cemetery"}>
    <Show when={props.emptyMessage}>
      {(message) => (
        <p class="page-state" role="status">
          {message()}
        </p>
      )}
    </Show>
    <Index each={props.histories}>
      {(history) => {
        const garden = () => {
          const entry = history();
          return entry.kind === "garden" ? entry : undefined;
        };
        const cemetery = () => {
          const entry = history();
          return entry.kind === "cemetery" ? entry : undefined;
        };
        return (
          <>
            <Show when={garden()}>
              {(entry) => (
                <PlantCard
                  plant={entry().plant}
                  watering={
                    props.attentionProjection?.plants.find((s) => s.plant === entry().plant.id)
                      ?.watering
                  }
                  measuredAt={props.attentionProjection?.measuredAt}
                  operationPage={entry().page}
                  substrateComponents={props.substrateComponents}
                  pesticides={props.pesticides}
                  getOperations={(window) => props.getOperations(entry().plant.id, window)}
                  operationChange={props.operationChange}
                  onViewPhotos={props.onViewPhotos}
                  onLog={() => {
                    props.onLog(entry().plant.id);
                  }}
                  onArchive={() => {
                    props.onArchive(entry().plant);
                  }}
                  onEditPlant={props.onEditPlant}
                  onEdit={props.onEdit}
                />
              )}
            </Show>
            <Show when={cemetery()}>
              {(entry) => (
                <PlantCard
                  kind="cemetery"
                  plant={entry().plant}
                  dates={entry().dates}
                  operationPage={entry().page}
                  substrateComponents={props.substrateComponents}
                  pesticides={props.pesticides}
                  getOperations={(window) => props.getOperations(entry().plant.id, window)}
                  operationChange={props.operationChange}
                  onViewPhotos={props.onViewPhotos}
                  onEdit={props.onEdit}
                />
              )}
            </Show>
          </>
        );
      }}
    </Index>
  </section>
);
