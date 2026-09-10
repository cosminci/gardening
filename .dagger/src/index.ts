import {
  argument,
  type Container,
  type Directory,
  func,
  object,
  type Secret,
} from "@dagger.io/dagger";
import { GHCR_REPOSITORY, GHCR_USER, WORKSPACE_IGNORE } from "./buildEnv";
import { backendCheck } from "./hooks/backend";
import { contractDrift } from "./hooks/contract";
import { changedPaths, gitDescribe, headExactTag, headSha, isClean } from "./hooks/git";
import { frontendCheck } from "./hooks/frontend";
import { runtimeImage } from "./hooks/image";
import { pipelineCheck } from "./hooks/pipeline";
import { componentsOf, parseChangedPaths, selectAffected, shouldCheckContract } from "./selection";
import { deriveVersion } from "./version";

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
    @argument({ defaultPath: "/", ignore: WORKSPACE_IGNORE }) source: Directory,
    base = "",
  ): Promise<string> {
    const components = componentsOf(
      selectAffected(parseChangedPaths(await changedPaths(source, base))),
    );
    return components.length === 0 ? "none" : components.join(",");
  }

  /** Runs the checks for the affected components only; `all=true` runs everything. */
  @func()
  async verify(
    @argument({ defaultPath: "/", ignore: WORKSPACE_IGNORE }) source: Directory,
    base = "",
    all = false,
  ): Promise<string> {
    const selection = all
      ? ({ kind: "all" } as const)
      : selectAffected(parseChangedPaths(await changedPaths(source, base)));
    const components = componentsOf(selection);
    const done: string[] = [];
    if (components.includes("backend")) {
      await this.backendCheck(source);
      done.push("backend");
    }
    if (shouldCheckContract(selection)) {
      await this.contractDrift(source);
      done.push("contract");
    }
    if (components.includes("frontend")) {
      await this.frontendCheck(source);
      done.push("frontend");
    }
    if (components.includes("pipeline")) {
      await this.pipelineCheck(source);
      done.push("pipeline");
    }
    return done.length === 0 ? "verify: nothing affected" : `verify: ${done.join(", ")} ok`;
  }

  @func()
  backendCheck(
    @argument({ defaultPath: "/", ignore: WORKSPACE_IGNORE }) source: Directory,
  ): Promise<string> {
    return backendCheck(source).stdout();
  }

  @func()
  frontendCheck(
    @argument({ defaultPath: "/", ignore: WORKSPACE_IGNORE }) source: Directory,
  ): Promise<string> {
    return frontendCheck(source).stdout();
  }

  @func()
  contractDrift(
    @argument({ defaultPath: "/", ignore: WORKSPACE_IGNORE }) source: Directory,
  ): Promise<string> {
    return contractDrift(source).stdout();
  }

  @func()
  pipelineCheck(
    @argument({ defaultPath: "/", ignore: WORKSPACE_IGNORE }) source: Directory,
  ): Promise<string> {
    return pipelineCheck(source).stdout();
  }

  /** Derives the version string from git. */
  @func()
  async version(
    @argument({ defaultPath: "/", ignore: WORKSPACE_IGNORE }) source: Directory,
  ): Promise<string> {
    return deriveVersion(await gitDescribe(source));
  }

  /** Builds the versioned, slim, non-root runtime image (linux/amd64, the NAS arch). It does not
   * run the checks — run `verify --all` first for a release.
   */
  @func()
  async buildImage(
    @argument({ defaultPath: "/", ignore: WORKSPACE_IGNORE }) source: Directory,
  ): Promise<Container> {
    return runtimeImage(source, {
      version: deriveVersion(await gitDescribe(source)),
      revision: (await headSha(source)).trim(),
      created: new Date().toISOString(),
    });
  }

  /** Refuses to proceed unless the working tree is clean and HEAD sits exactly on a tag. */
  @func()
  async releaseGuard(
    @argument({ defaultPath: "/", ignore: WORKSPACE_IGNORE }) source: Directory,
  ): Promise<string> {
    if (!(await isClean(source))) throw new Error("release refused: working tree is dirty");
    const tag = (await headExactTag(source)).trim();
    if (tag === "") throw new Error("release refused: HEAD is not on a tag");
    return `release ok: ${tag}`;
  }

  /** Builds a guarded release image and pushes it to the private GHCR package. */
  @func()
  async publish(
    @argument({ defaultPath: "/", ignore: WORKSPACE_IGNORE }) source: Directory,
    token: Secret,
  ): Promise<string> {
    await this.releaseGuard(source);
    const version = deriveVersion(await gitDescribe(source));
    const image = (await this.buildImage(source)).withRegistryAuth("ghcr.io", GHCR_USER, token);
    const versioned = await image.publish(`${GHCR_REPOSITORY}:${version}`);
    const latest = await image.publish(`${GHCR_REPOSITORY}:latest`);
    return `published ${versioned} and ${latest}`;
  }

  /** Prints how the NAS pulls a given version (WUD auto-update is preferred). */
  @func()
  deploy(version: string): string {
    return [
      "# Preferred: WUD auto-updates the container (image is labelled wud.watch=true).",
      "# Manual pull + recreate on the NAS:",
      `ssh nas 'docker pull ${GHCR_REPOSITORY}:${version} && docker compose up -d plant-journal'`,
    ].join("\n");
  }
}
