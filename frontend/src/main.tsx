import { render } from "solid-js/web";
import { makeHttpOperationClient } from "./adapters/http/HttpOperationClient";
import { makeHttpPesticideClient } from "./adapters/http/HttpPesticideClient";
import { makeHttpPlantAttentionClient } from "./adapters/http/HttpPlantAttentionClient";
import { makeHttpPlantClient } from "./adapters/http/HttpPlantClient";
import { makeHttpSubstrateComponentClient } from "./adapters/http/HttpSubstrateComponentClient";
import { App } from "./app/App";
import "./app/controls.css";

const root = document.getElementById("root");
if (root !== null) {
  render(
    () => (
      <App
        plants={makeHttpPlantClient()}
        operations={makeHttpOperationClient()}
        attention={makeHttpPlantAttentionClient()}
        substrates={makeHttpSubstrateComponentClient()}
        pesticideCatalog={makeHttpPesticideClient()}
      />
    ),
    root,
  );
}
