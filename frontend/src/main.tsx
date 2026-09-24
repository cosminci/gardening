import { render } from "solid-js/web";
import { makeHttpJournalClient } from "./adapters/http/HttpJournalClient";
import { makeHttpSubstrateComponentClient } from "./adapters/http/HttpSubstrateComponentClient";
import { App } from "./app/App";
import "./app/controls.css";

const root = document.getElementById("root");
if (root !== null) {
  render(
    () => <App journal={makeHttpJournalClient()} substrates={makeHttpSubstrateComponentClient()} />,
    root,
  );
}
