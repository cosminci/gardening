import * as Dagger from "@dagger.io/dagger";
import * as BuildEnv from "../buildEnv";
import { backendStage } from "./backend";
import { frontendBuild } from "./frontend";

export interface ImageInfo {
  readonly version: string;
  readonly revision: string;
  readonly created: string;
}

/** Assembles the slim, non-root runtime image on a distroless glibc base: the staged backend
 * (which bundles its own jlink runtime — no JRE, sbt, node, or compiler ships here) plus the built
 * static assets. Ownership is set during the copy so no duplicate chown layer is produced.
 */
export function runtimeImage(source: Dagger.Directory, info: ImageInfo): Dagger.Container {
  const app = backendStage(source);
  const staticAssets = frontendBuild(source);
  return (
    Dagger.dag
      .container({ platform: BuildEnv.TARGET_PLATFORM as Dagger.Platform })
      .from(BuildEnv.RUNTIME_IMAGE)
      .withDirectory("/app", app, { owner: "1000:1000" })
      .withDirectory("/app/static", staticAssets, { owner: "1000:1000" })
      .withDirectory("/data", Dagger.dag.directory(), { owner: "1000:1000" })
      .withEnvVariable("GARDENING_APP_VERSION", info.version)
      .withEnvVariable("GARDENING_STATIC_DIR", "/app/static")
      .withEnvVariable("GARDENING_DB_PATH", "/data/gardening.db")
      .withEnvVariable("GARDENING_PORT", String(BuildEnv.APP_PORT))
      .withExposedPort(BuildEnv.APP_PORT)
      .withUser("1000:1000")
      // Launch the bundled jlink runtime directly (no shell), so the runtime base can be distroless.
      .withEntrypoint([
        "/app/jre/bin/java",
        "--enable-native-access=ALL-UNNAMED",
        "-cp",
        "/app/lib/*",
        "gardening.app.Main",
      ])
      .withLabel("org.opencontainers.image.title", BuildEnv.IMAGE_NAME)
      .withLabel("org.opencontainers.image.description", "plant-journal — house-plant care journal")
      .withLabel("org.opencontainers.image.version", info.version)
      .withLabel("org.opencontainers.image.revision", info.revision)
      .withLabel("org.opencontainers.image.created", info.created)
      .withLabel("org.opencontainers.image.source", BuildEnv.SOURCE_URL)
  );
}
