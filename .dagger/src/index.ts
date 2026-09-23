import type * as Dagger from "@dagger.io/dagger";
import { argument, dag, func, object } from "@dagger.io/dagger";
import { GHCR_REPOSITORY, GHCR_USER, TARGET_PLATFORM, WORKSPACE_IGNORE } from "./buildEnv";
import { backendCheck } from "./hooks/backend";
import { contractDrift } from "./hooks/contract";
import * as Git from "./hooks/git";
import { frontendCheck } from "./hooks/frontend";
import { runtimeImage } from "./hooks/image";
import { pipelineCheck } from "./hooks/pipeline";
import { publishedTags } from "./hooks/registry";
import * as Selection from "./selection";
import { assertReleaseSource, deriveVersion, planLatestRepair, planPublication } from "./version";

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

  /** Refuses to release anything except the explicitly selected annotated tag at HEAD. */
  @func()
  async releaseGuard(
    @argument({ defaultPath: "/", ignore: WORKSPACE_IGNORE }) source: Dagger.Directory,
    tag: string,
  ): Promise<string> {
    planPublication(tag, []);
    assertReleaseSource({
      tag,
      clean: await Git.isClean(source),
      tagType: (await Git.tagType(source, tag)).trim(),
      headRevision: (await Git.headSha(source)).trim(),
      tagRevision: (await Git.tagRevision(source, tag)).trim(),
      mainAncestor: (await Git.mainAncestor(source)).trim(),
    });
    return `release ok: ${tag}`;
  }

  /** Verifies and publishes a guarded release, without overwriting an existing version. */
  @func()
  async publish(
    @argument({ defaultPath: "/", ignore: WORKSPACE_IGNORE }) source: Dagger.Directory,
    tag: string,
    token: Dagger.Secret,
  ): Promise<string> {
    await this.releaseGuard(source, tag);
    const plan = planPublication(tag, await publishedTags(token));
    await this.verify(source, "", true);
    const image = runtimeImage(source, {
      version: plan.version,
      revision: (await Git.headSha(source)).trim(),
      created: new Date().toISOString(),
    }).withRegistryAuth("ghcr.io", GHCR_USER, token);
    const versioned = await image.publish(`${GHCR_REPOSITORY}:${plan.version}`);
    if (!plan.updateLatest) return `published ${versioned}`;
    const latest = await image.publish(`${GHCR_REPOSITORY}:latest`);
    return `published ${versioned} and ${latest}`;
  }

  /** Repairs a partially published stable release without replacing its versioned image. */
  @func()
  async repairLatest(
    @argument({ defaultPath: "/", ignore: WORKSPACE_IGNORE }) source: Dagger.Directory,
    tag: string,
    token: Dagger.Secret,
  ): Promise<string> {
    await this.releaseGuard(source, tag);
    const version = planLatestRepair(tag, await publishedTags(token));
    await this.verify(source, "", true);
    const image = dag
      .container({ platform: TARGET_PLATFORM as Dagger.Platform })
      .withRegistryAuth("ghcr.io", GHCR_USER, token)
      .from(`${GHCR_REPOSITORY}:${version}`);
    const revision = (await Git.headSha(source)).trim();
    if (
      (await image.label("org.opencontainers.image.revision")) !== revision ||
      (await image.envVariable("GARDENING_APP_VERSION")) !== version
    ) {
      throw new Error("release refused: published image provenance differs from selected tag");
    }
    return image.publish(`${GHCR_REPOSITORY}:latest`);
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
