import { render } from "solid-js/web";
import { makeHttpJournalClient } from "./adapters/http/HttpJournalClient";
import { makeHttpPesticideClient } from "./adapters/http/HttpPesticideClient";
import { makeHttpPlantAttentionClient } from "./adapters/http/HttpPlantAttentionClient";
import { makeHttpSubstrateComponentClient } from "./adapters/http/HttpSubstrateComponentClient";
import { App } from "./app/App";
import "./app/controls.css";

const root = document.getElementById("root");
if (root !== null) {
  render(
    () => (
      <App
        journal={makeHttpJournalClient()}
        attention={makeHttpPlantAttentionClient()}
        substrates={makeHttpSubstrateComponentClient()}
        pesticideCatalog={makeHttpPesticideClient()}
      />
    ),
    root,
  );
}
