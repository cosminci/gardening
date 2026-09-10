import { render } from "solid-js/web";
import { httpHealthClient } from "./adapters/httpHealthClient";
import { App } from "./app/App";

const root = document.getElementById("root");
if (root !== null) {
  render(() => <App client={httpHealthClient("")} />, root);
}
