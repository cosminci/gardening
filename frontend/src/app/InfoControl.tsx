import { createSignal } from "solid-js";
import type { Component } from "solid-js";
import "./info-control.css";

interface InfoControlProps {
  readonly id: string;
  readonly label: string;
  readonly notes: string | null;
}

export const InfoControl: Component<InfoControlProps> = (props) => {
  const [open, setOpen] = createSignal(false);

  return (
    <span class="info-control" data-open={open() ? "" : undefined}>
      <button
        class="info-control__trigger"
        type="button"
        aria-label={props.label}
        aria-describedby={props.id}
        onClick={() => {
          setOpen(true);
        }}
        onFocus={() => {
          setOpen(true);
        }}
        onBlur={() => {
          setOpen(false);
        }}
        onKeyDown={(event) => {
          if (event.key === "Escape") {
            event.stopPropagation();
            setOpen(false);
            event.currentTarget.blur();
          }
        }}
      >
        i
      </button>
      <span id={props.id} class="info-control__content" role="tooltip">
        {props.notes ?? "No notes."}
      </span>
    </span>
  );
};
