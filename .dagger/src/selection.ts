import type { Component, Selection } from "./domain";
import { ALL_COMPONENTS } from "./domain";

/** Split a comma/newline-separated `git diff --name-only` payload into clean paths. */
export function parseChangedPaths(raw: string): readonly string[] {
  return raw
    .split(/[\n,]/)
    .map((line) => line.trim())
    .filter((line) => line !== "");
}

/** Components a single changed path affects. The contract sits between the two app components, so
 * a contract change affects both; pipeline sources and workflows affect the pipeline. Paths outside these
 * roots (docs and unrelated tooling) affect nothing.
 */
export function componentsForPath(path: string): readonly Component[] {
  if (path.startsWith("contract/")) return ["backend", "frontend"];
  if (path.startsWith("backend/")) return ["backend"];
  if (path.startsWith("frontend/")) return ["frontend"];
  if (
    path === "dagger.json" ||
    path.startsWith(".dagger/") ||
    path.startsWith("scripts/") ||
    path.startsWith(".github/workflows/")
  )
    return ["pipeline"];
  return [];
}

export function selectAffected(changedPaths: readonly string[]): Selection {
  const affected = new Set<Component>();
  for (const path of changedPaths) {
    for (const component of componentsForPath(path)) {
      affected.add(component);
    }
  }
  if (affected.size === 0) return { kind: "none" };
  if (affected.size === ALL_COMPONENTS.length) return { kind: "all" };
  return {
    kind: "some",
    components: ALL_COMPONENTS.filter((component) => affected.has(component)),
  };
}

export function componentsOf(selection: Selection): readonly Component[] {
  switch (selection.kind) {
    case "none":
      return [];
    case "all":
      return ALL_COMPONENTS;
    case "some":
      return selection.components;
  }
}

/** The contract is regenerated from the backend, so drift is checked whenever the backend (or the
 * committed contract, which maps to both app components) is affected.
 */
export function shouldCheckContract(selection: Selection): boolean {
  return componentsOf(selection).includes("backend");
}
