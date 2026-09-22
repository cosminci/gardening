import { render } from "solid-js/web";
import { makeHttpJournalClient } from "./adapters/http/HttpJournalClient";
import { App } from "./app/App";
import "./app/controls.css";

const root = document.getElementById("root");
if (root !== null) {
  render(() => <App journal={makeHttpJournalClient()} />, root);
}
