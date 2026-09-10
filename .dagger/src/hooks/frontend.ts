import { type Container, dag, type Directory } from "@dagger.io/dagger";
import { NODE_IMAGE } from "../buildEnv";

/** Node base with the frontend and its sibling contract laid out as on disk, so the
 * `@contract` (../contract/generated) alias resolves.
 */
function nodeApp(source: Directory): Container {
  return dag
    .container()
    .from(NODE_IMAGE)
    .withMountedCache("/root/.npm", dag.cacheVolume("gardening-npm"))
    .withDirectory("/app/contract", source.directory("contract"))
    .withDirectory("/app/frontend", source.directory("frontend"))
    .withWorkdir("/app/frontend");
}

/** Full frontend gate: tsc, eslint, prettier check, dependency-cruiser, 100% coverage. */
export function frontendCheck(source: Directory): Container {
  return nodeApp(source).withExec(["npm", "ci"]).withExec(["npm", "run", "verify"]);
}

/** Builds the static single-page-app bundle. */
export function frontendBuild(source: Directory): Directory {
  return nodeApp(source)
    .withExec(["npm", "ci"])
    .withExec(["npm", "run", "build"])
    .directory("/app/frontend/dist");
}
