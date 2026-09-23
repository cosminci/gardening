import { type Container, dag, type Directory } from "@dagger.io/dagger";

const GIT_IMAGE = "alpine/git:latest";

function gitContainer(source: Directory): Container {
  return dag.container().from(GIT_IMAGE).withMountedDirectory("/repo", source).withWorkdir("/repo");
}

/** `git diff --name-only` between HEAD and the base (default: merge-base with origin/main, with
 * fallbacks so it still works on a shallow or single-commit history).
 */
export async function changedPaths(source: Directory, base: string): Promise<string> {
  const ref = base === "" ? "origin/main" : base;
  const script = `git diff --name-only "$(git merge-base ${ref} HEAD 2>/dev/null || git rev-list --max-parents=0 HEAD | tail -1)" HEAD`;
  return gitContainer(source).withExec(["sh", "-c", script]).stdout();
}

export async function gitDescribe(source: Directory): Promise<string> {
  return gitContainer(source)
    .withExec(["git", "describe", "--tags", "--always", "--dirty"])
    .stdout();
}

export async function headSha(source: Directory): Promise<string> {
  return gitContainer(source).withExec(["git", "rev-parse", "HEAD"]).stdout();
}

export async function isClean(source: Directory): Promise<boolean> {
  const status = await gitContainer(source).withExec(["git", "status", "--porcelain"]).stdout();
  return status.trim() === "";
}

export async function tagType(source: Directory, tag: string): Promise<string> {
  return gitContainer(source)
    .withExec(["git", "cat-file", "-t", `refs/tags/${tag}`])
    .stdout();
}

export async function tagRevision(source: Directory, tag: string): Promise<string> {
  return gitContainer(source)
    .withExec(["git", "rev-parse", "--verify", `refs/tags/${tag}^{commit}`])
    .stdout();
}

export async function mainAncestor(source: Directory): Promise<string> {
  return gitContainer(source).withExec(["git", "merge-base", "origin/main", "HEAD"]).stdout();
}
