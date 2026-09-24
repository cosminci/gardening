import { spawnSync } from "node:child_process";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { delimiter, join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";

const releaseScript = join(process.cwd(), "..", "scripts", "release.sh");
const workspaces: string[] = [];

describe("release script", () => {
  it("should verify the exact release and every component gate before publishing", () => {
    const workspace = buildReleaseWorkspace();

    const result = runRelease(workspace, "v1.0.0-rc.1");

    const expectedSteps = [
      "dagger call release-guard --tag v1.0.0-rc.1",
      "npm run verify",
      "dagger call publish --tag v1.0.0-rc.1 --token=env:GITHUB_PERSONAL_PAT",
    ];
    expect(result.status).toBe(0);
    expect(readSteps(workspace)).toEqual(expectedSteps);
  });

  it("should publish using the short-lived GitHub Actions token", () => {
    const workspace = buildReleaseWorkspace();

    const result = runRelease(workspace, "v1.0.0-rc.1", "", "none", "ci-token");

    const expectedPublishStep = "dagger call publish --tag v1.0.0-rc.1 --token=env:GITHUB_TOKEN";
    expect(result.status).toBe(0);
    expect(readSteps(workspace)).toContain(expectedPublishStep);
  });

  it("should refuse to start when publishing credentials are absent", () => {
    const workspace = buildReleaseWorkspace();

    const result = runRelease(workspace, "v1.0.0-rc.1", "");

    expect(result.status).not.toBe(0);
    expect(existsSync(join(workspace, "steps"))).toBe(false);
  });

  it("should not publish if the pipeline's own gate fails", () => {
    const workspace = buildReleaseWorkspace();

    const result = runRelease(workspace, "v1.0.0-rc.1", "test-token", "npm");

    const expectedSteps = ["dagger call release-guard --tag v1.0.0-rc.1", "npm run verify"];
    expect(result.status).not.toBe(0);
    expect(readSteps(workspace)).toEqual(expectedSteps);
  });

  it("should not run any check after a rejected release tag", () => {
    const workspace = buildReleaseWorkspace();

    const result = runRelease(workspace, "v1.0.0-rc.1", "test-token", "release-guard");

    expect(result.status).not.toBe(0);
    expect(readSteps(workspace)).toEqual(["dagger call release-guard --tag v1.0.0-rc.1"]);
  });

  it("should require complete Git metadata instead of uploading a worktree pointer", () => {
    const workspace = buildReleaseWorkspace();
    rmSync(join(workspace, ".git"), { recursive: true });
    writeFileSync(join(workspace, ".git"), "gitdir: /elsewhere\n");

    const result = runRelease(workspace, "v1.0.0-rc.1");

    expect(result.status).not.toBe(0);
    expect(existsSync(join(workspace, "steps"))).toBe(false);
  });
});

afterEach(() => {
  for (const workspace of workspaces) rmSync(workspace, { recursive: true, force: true });
  workspaces.length = 0;
});

function buildReleaseWorkspace(): string {
  const workspace = mkdtempSync(join(tmpdir(), "plant-journal-release-"));
  workspaces.push(workspace);
  mkdirSync(join(workspace, ".git"));
  mkdirSync(join(workspace, ".dagger"));
  mkdirSync(join(workspace, "bin"));
  writeFileSync(join(workspace, "bin", "git"), '#!/bin/sh\nprintf "%s\\n" "$RELEASE_ROOT"\n', {
    mode: 0o755,
  });
  writeFileSync(
    join(workspace, "bin", "dagger"),
    '#!/bin/sh\nprintf "dagger %s\\n" "$*" >> "$RELEASE_LOG"\n[ "$RELEASE_FAIL" != "$2" ]\n',
    { mode: 0o755 },
  );
  writeFileSync(
    join(workspace, "bin", "npm"),
    '#!/bin/sh\nprintf "npm %s\\n" "$*" >> "$RELEASE_LOG"\n[ "$RELEASE_FAIL" != "npm" ]\n',
    { mode: 0o755 },
  );
  return workspace;
}

function runRelease(
  workspace: string,
  tag: string,
  token = "test-token",
  fail = "none",
  workflowToken = "",
) {
  const inheritedPath = process.env.PATH;
  if (!inheritedPath) throw new Error("PATH must be set to run release use cases");
  return spawnSync("bash", [releaseScript, tag], {
    cwd: workspace,
    encoding: "utf8",
    env: {
      ...process.env,
      PATH: `${join(workspace, "bin")}${delimiter}${inheritedPath}`,
      RELEASE_ROOT: workspace,
      RELEASE_LOG: join(workspace, "steps"),
      RELEASE_FAIL: fail,
      GITHUB_PERSONAL_PAT: token,
      GITHUB_TOKEN: workflowToken,
    },
  });
}

function readSteps(workspace: string): string[] {
  return readFileSync(join(workspace, "steps"), "utf8").trim().split("\n");
}
