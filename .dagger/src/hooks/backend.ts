import { type Container, dag, type Directory, type Platform } from "@dagger.io/dagger";
import { JDK_IMAGE, TARGET_PLATFORM } from "../buildEnv";

/** JDK 25 + sbt base (prebuilt image) with cached coursier / sbt / ivy directories. A platform is
 * passed for image builds (jlink emits a platform-specific runtime); checks omit it and run on the
 * host arch.
 */
function sbtBase(platform?: Platform): Container {
  const base = platform === undefined ? dag.container() : dag.container({ platform });
  return base
    .from(JDK_IMAGE)
    .withUser("root")
    .withEnvVariable("HOME", "/root")
    .withMountedCache("/root/.cache/coursier", dag.cacheVolume("gardening-coursier"))
    .withMountedCache("/root/.sbt", dag.cacheVolume("gardening-sbt"))
    .withMountedCache("/root/.ivy2", dag.cacheVolume("gardening-ivy2"));
}

function backendSources(base: Container, source: Directory): Container {
  return base.withDirectory("/work", source.directory("backend")).withWorkdir("/work");
}

/** A host-arch sbt container with the backend sources mounted at `/work`. Shared by the gate and
 * the contract regeneration.
 */
export function backendWork(source: Directory): Container {
  return backendSources(sbtBase(), source);
}

/** Full backend gate: scalafmt check, scalafix check, warnings-as-errors compile, 100% coverage. */
export function backendCheck(source: Directory): Container {
  return backendWork(source).withExec([
    "sbt",
    "-batch",
    "-Dsbt.color=false",
    "-Dsbt.supershell=false",
    "compile",
    "scalafixAll --check",
    "scalafmtCheckAll",
    "coverage",
    "test",
    "coverageReport",
  ]);
}

/** Stages the backend runtime application directory (bin + libs + bundled jlink runtime) for the
 * given platform via sbt-native-packager.
 */
export function backendStage(source: Directory): Directory {
  return backendSources(sbtBase(TARGET_PLATFORM as Platform), source)
    .withExec(["sbt", "-batch", "-Dsbt.color=false", "stage"])
    .directory("/work/target/universal/stage");
}
