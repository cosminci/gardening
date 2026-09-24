import { type Container, dag, type Directory } from "@dagger.io/dagger";
import { NODE_IMAGE } from "../buildEnv";

/** Runs the pipeline module's own tests (Vitest, 100% on the pure selection/version logic) in a
 * Node container. The module's type/lint gates need the generated Dagger SDK and run on the host,
 * so they are not repeated here.
 */
export function pipelineCheck(source: Directory): Container {
  return dag
    .container()
    .from(NODE_IMAGE)
    .withMountedCache("/root/.npm", dag.cacheVolume("gardening-npm"))
    .withDirectory("/ci", source.directory(".dagger"))
    .withDirectory("/scripts", source.directory("scripts"))
    .withWorkdir("/ci")
    .withExec(["npm", "ci", "--no-audit", "--no-fund"])
    .withExec(["npx", "vitest", "run", "--coverage"]);
}
