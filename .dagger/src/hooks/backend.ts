import * as Dagger from "@dagger.io/dagger";
import { JDK_IMAGE, TARGET_PLATFORM } from "../buildEnv";

/** JDK 25 + sbt base (prebuilt image) with cached coursier / sbt / ivy directories. A platform is
 * passed for image builds (jlink emits a platform-specific runtime); checks omit it and run on the
 * host arch.
 */
function sbtBase(platform?: Dagger.Platform): Dagger.Container {
  const base = platform === undefined ? Dagger.dag.container() : Dagger.dag.container({ platform });
  return base
    .from(JDK_IMAGE)
    .withUser("root")
    .withEnvVariable("HOME", "/root")
    .withMountedCache("/root/.cache/coursier", Dagger.dag.cacheVolume("gardening-coursier"))
    .withMountedCache("/root/.sbt", Dagger.dag.cacheVolume("gardening-sbt"))
    .withMountedCache("/root/.ivy2", Dagger.dag.cacheVolume("gardening-ivy2"));
}

function backendSources(base: Dagger.Container, source: Dagger.Directory): Dagger.Container {
  return base.withDirectory("/work", source.directory("backend")).withWorkdir("/work");
}

/** A host-arch sbt container with the backend sources mounted at `/work`. Shared by the gate and
 * the contract regeneration.
 */
export function backendWork(source: Dagger.Directory): Dagger.Container {
  return backendSources(sbtBase(), source);
}

/** Full backend gate: scalafmt check, scalafix check, warnings-as-errors compile, 100% coverage.
 *
 * `coverage` runs before any compile so the sources are compiled exactly once, with scoverage
 * instrumentation — that single instrumented compile already satisfies the warnings-as-errors and
 * wartremover checks, so no separate plain compile is needed. `scalafmtCheckAll` (no compile) runs
 * first to fail fast on formatting; `scalafixAll --check` runs last, reusing the instrumented
 * compile's semanticdb (emitted at typer, unaffected by instrumentation). The uninstrumented
 * assembly compile only happens on publish, in `backendStage`.
 */
export function backendCheck(source: Dagger.Directory): Dagger.Container {
  return backendWork(source).withExec([
    "sbt",
    "-batch",
    "-Dsbt.color=false",
    "-Dsbt.supershell=false",
    ";scalafmtCheckAll;coverage;test;coverageReport;scalafixAll --check",
  ]);
}

/** Stages the backend runtime application directory (bin + libs + bundled jlink runtime) for the
 * given platform via sbt-native-packager.
 */
export function backendStage(source: Dagger.Directory): Dagger.Directory {
  return backendSources(sbtBase(TARGET_PLATFORM as Dagger.Platform), source)
    .withExec(["sbt", "-batch", "-Dsbt.color=false", "stage"])
    .directory("/work/target/universal/stage");
}
