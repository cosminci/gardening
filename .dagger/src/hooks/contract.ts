import { type Container, dag, type Directory } from "@dagger.io/dagger";
import { NODE_IMAGE } from "../buildEnv";
import { backendWork } from "./backend";

const COMPARE_SCRIPT = [
  "const fs=require('fs');",
  "const pairs=[['/tmp/openapi.yaml','openapi.yaml'],['/tmp/api.ts','generated/api.ts']];",
  "for(const [regenerated,committed] of pairs){",
  "  if(fs.readFileSync(regenerated,'utf8')!==fs.readFileSync(committed,'utf8')){",
  "    console.error('contract drift: '+committed+' is out of date; run `npm run generate`');",
  "    process.exit(1);",
  "  }",
  "}",
  "console.log('contract up to date');",
].join("");

/** Regenerates the OpenAPI document and the TypeScript client from the backend and fails on any
 * drift against the committed contract.
 */
export function contractDrift(source: Directory): Container {
  const regeneratedOpenApi = backendWork(source)
    .withExec([
      "sbt",
      "-batch",
      "-Dsbt.color=false",
      "runMain gardening.app.GenerateOpenApi /out/openapi.yaml",
    ])
    .file("/out/openapi.yaml");

  return dag
    .container()
    .from(NODE_IMAGE)
    .withMountedCache("/root/.npm", dag.cacheVolume("gardening-npm"))
    .withDirectory("/app/contract", source.directory("contract"))
    .withFile("/tmp/openapi.yaml", regeneratedOpenApi)
    .withWorkdir("/app/contract")
    .withExec(["npm", "ci"])
    .withExec(["npx", "openapi-typescript", "/tmp/openapi.yaml", "-o", "/tmp/api.ts"])
    .withExec(["node", "-e", COMPARE_SCRIPT]);
}
