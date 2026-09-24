import type * as Dagger from "@dagger.io/dagger";
import { argument, func, object } from "@dagger.io/dagger";
import { GHCR_REPOSITORY, GHCR_USER, WORKSPACE_IGNORE } from "./buildEnv";
import { backendCheck } from "./hooks/backend";
import { contractDrift } from "./hooks/contract";
import * as Git from "./hooks/git";
import { frontendCheck } from "./hooks/frontend";
import { runtimeImage } from "./hooks/image";
import { localSetupCheck, pipelineCheck } from "./hooks/pipeline";
import * as Selection from "./selection";
import { assertReleaseVersion, deriveVersion, releaseVersion } from "./version";

/** CI pipeline for the plant-journal app. One composable module over three components — backend,
 * frontend, and the pipeline itself — plus the contract that binds the app halves. Verifying and
 * releasing are separate concerns: `verify` runs the affected checks; `buildImage`/`publish` build
 * and ship and do NOT re-run the checks, so a release runs `verify --all` first (see ci/specs).
 */
@object()
export class Gardening {
  /** Maps changed paths (vs `base`; default merge-base with origin/main) to affected components. */
  @func()
  async changed(
    @argument({ defaultPath: "/", ignore: WORKSPACE_IGNORE }) source: Dagger.Directory,
    base = "",
  ): Promise<string> {
    const components = Selection.componentsOf(
      Selection.selectAffected(Selection.parseChangedPaths(await Git.changedPaths(source, base))),
    );
    return components.length === 0 ? "none" : components.join(",");
  }

  /** Runs the checks for the affected components only; `all=true` runs everything. */
  @func()
  async verify(
    @argument({ defaultPath: "/", ignore: WORKSPACE_IGNORE }) source: Dagger.Directory,
    base = "",
    all = false,
  ): Promise<string> {
    const selection = all
      ? ({ kind: "all" } as const)
      : Selection.selectAffected(Selection.parseChangedPaths(await Git.changedPaths(source, base)));
    const components = Selection.componentsOf(selection);
    const done: string[] = [];
    if (components.includes("backend")) {
      await this.backendCheck(source);
      done.push("backend");
    }
    if (Selection.shouldCheckContract(selection)) {
      await this.contractDrift(source);
      done.push("contract");
    }
    if (components.includes("frontend")) {
      await this.frontendCheck(source);
      done.push("frontend");
    }
    if (components.includes("pipeline")) {
      await this.pipelineCheck(source);
      await localSetupCheck(source).stdout();
      done.push("pipeline");
    }
    return done.length === 0 ? "verify: nothing affected" : `verify: ${done.join(", ")} ok`;
  }

  @func()
  backendCheck(
    @argument({ defaultPath: "/", ignore: WORKSPACE_IGNORE }) source: Dagger.Directory,
  ): Promise<string> {
    return backendCheck(source).stdout();
  }

  @func()
  frontendCheck(
    @argument({ defaultPath: "/", ignore: WORKSPACE_IGNORE }) source: Dagger.Directory,
  ): Promise<string> {
    return frontendCheck(source).stdout();
  }

  @func()
  contractDrift(
    @argument({ defaultPath: "/", ignore: WORKSPACE_IGNORE }) source: Dagger.Directory,
  ): Promise<string> {
    return contractDrift(source).stdout();
  }

  @func()
  pipelineCheck(
    @argument({ defaultPath: "/", ignore: WORKSPACE_IGNORE }) source: Dagger.Directory,
  ): Promise<string> {
    return pipelineCheck(source).stdout();
  }

  /** Derives the version string from git. */
  @func()
  async version(
    @argument({ defaultPath: "/", ignore: WORKSPACE_IGNORE }) source: Dagger.Directory,
  ): Promise<string> {
    return deriveVersion(await Git.gitDescribe(source));
  }

  /** Builds the versioned, slim, non-root runtime image (linux/amd64, the NAS arch). It does not
   * run the checks — run `verify --all` first for a release.
   */
  @func()
  async buildImage(
    @argument({ defaultPath: "/", ignore: WORKSPACE_IGNORE }) source: Dagger.Directory,
  ): Promise<Dagger.Container> {
    return runtimeImage(source, {
      version: deriveVersion(await Git.gitDescribe(source)),
      revision: (await Git.headSha(source)).trim(),
      created: new Date().toISOString(),
    });
  }

  /** Creates a UTC release version, once per manually dispatched publish. */
  @func({ cache: "never" })
  releaseVersion(): string {
    return releaseVersion(new Date());
  }

  /** Publishes the selected version; the manual release check verifies first and refuses existing tags. */
  @func({ cache: "never" })
  async publish(
    @argument({ defaultPath: "/", ignore: WORKSPACE_IGNORE }) source: Dagger.Directory,
    tag: string,
    token: Dagger.Secret,
    registryUser = GHCR_USER,
  ): Promise<string> {
    assertReleaseVersion(tag);
    const image = runtimeImage(source, {
      version: tag,
      revision: (await Git.headSha(source)).trim(),
      created: new Date().toISOString(),
    }).withRegistryAuth("ghcr.io", registryUser, token);
    return image.publish(`${GHCR_REPOSITORY}:${tag}`);
  }
}
