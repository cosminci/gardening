import { For, Match, Switch } from "solid-js";
import type { Component } from "solid-js";
import * as Journal from "../domain/Journal";
import { actionLabels } from "./JournalLabels";

export const ActionGlyph: Component<{ type: Exclude<Journal.ActionType, "noAction"> }> = (
  props,
) => (
  <Switch>
    <Match when={props.type === "watered"}>
      <path d="M6 9a3 3 0 0 0 0 6" />
      <path d="M16 6l3-1v3l-3 1" />
      <path d="M6 8a2 2 0 0 1 2-2h6a2 2 0 0 1 2 2v10a4 4 0 0 1-4 4h-2a4 4 0 0 1-4-4V8Z" />
    </Match>
    <Match when={props.type === "showered"}>
      <path d="M11 7v-1a4 4 0 0 1 4-4a4 4 0 0 1 4 4v15" />
      <path d="M7 11a4 4 0 0 1 8 0" />
      <path d="M6 11h10" />
      <path d="M8 11.5l-1.5 3M11 11.5v3M14 11.5l1.5 3" />
    </Match>
    <Match when={props.type === "fertilized"}>
      <path d="M10 3h4v3h-4z" />
      <path d="M8 6C6 8 6 13 7 17C7.5 20 9.5 21 12 21C14.5 21 16.5 20 17 17C18 13 18 8 16 6Z" />
      <circle cx="12" cy="14" r="3.4" />
      <path d="M12 17v-3" />
      <path d="M12 14c-1.2 0-2-0.8-2-2" />
      <path d="M12 14c1.2 0 2-0.8 2-2" />
    </Match>
    <Match when={props.type === "pesticide"}>
      <path d="M9.5 2h5v2h-5z" />
      <path d="M10 4h4v2h-4z" />
      <path d="M10 6L5 9V20A2 2 0 0 0 7 22H17A2 2 0 0 0 19 20V9L14 6Z" />
      <path d="M6.8 14C6.8 10.5 8.9 8 12 8C15.1 8 17.2 10.5 17.2 14C17.2 15.3 16.7 16 16.7 17.3H7.3C7.3 16 6.8 15.3 6.8 14Z" />
      <circle cx="9.3" cy="13" r="1.1" />
      <circle cx="14.7" cy="13" r="1.1" />
      <path d="M12 14.5l-0.8 1.5h1.6z" />
      <path d="M7.3 17.3h9.4v1.8a1.1 1.1 0 0 1-1.1 1.1H8.4a1.1 1.1 0 0 1-1.1-1.1z" />
      <path d="M9.3 17.3v1.8M12 17.3v2M14.7 17.3v1.8" />
    </Match>
    <Match when={props.type === "pruned"}>
      <circle cx="6" cy="6" r="2.5" />
      <circle cx="6" cy="18" r="2.5" />
      <path d="M8 8l12 11M8 16l12-11" />
    </Match>
  </Switch>
);

export const ActionIcons: Component<{ actions: ReadonlySet<Journal.ActionType> }> = (props) => (
  <For each={Journal.actionTypes.filter((type) => type !== "noAction" && props.actions.has(type))}>
    {(type) => (
      <svg
        class={`action-icon action-icon--${type}`}
        viewBox="0 0 24 24"
        role="img"
        aria-label={actionLabels[type]}
      >
        <ActionGlyph type={type as Exclude<Journal.ActionType, "noAction">} />
      </svg>
    )}
  </For>
);
