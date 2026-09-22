import { describe, expect, it } from "vitest";
import * as Selection from "../src/selection";

describe("parseChangedPaths", () => {
  it("splits on commas and newlines and drops blanks", () => {
    expect(Selection.parseChangedPaths("backend/a.scala,\nfrontend/b.ts,  , ")).toEqual([
      "backend/a.scala",
      "frontend/b.ts",
    ]);
  });
});

describe("componentsForPath", () => {
  it("maps a contract path to both app components", () => {
    expect(Selection.componentsForPath("contract/openapi.yaml")).toEqual(["backend", "frontend"]);
  });
  it("maps a backend path to backend", () => {
    expect(Selection.componentsForPath("backend/build.sbt")).toEqual(["backend"]);
  });
  it("maps a frontend path to frontend", () => {
    expect(Selection.componentsForPath("frontend/package.json")).toEqual(["frontend"]);
  });
  it("maps a pipeline path to pipeline", () => {
    expect(Selection.componentsForPath(".dagger/src/index.ts")).toEqual(["pipeline"]);
  });
  it("maps an unrelated path to nothing", () => {
    expect(Selection.componentsForPath("docs/design.md")).toEqual([]);
  });
});

describe("selectAffected", () => {
  it("returns none when nothing relevant changed", () => {
    expect(Selection.selectAffected(["README.md"])).toEqual({ kind: "none" });
  });
  it("returns a single component", () => {
    expect(Selection.selectAffected(["frontend/x.ts"])).toEqual({
      kind: "some",
      components: ["frontend"],
    });
  });
  it("returns a subset for the two app components", () => {
    expect(Selection.selectAffected(["backend/x.scala", "frontend/y.ts"])).toEqual({
      kind: "some",
      components: ["backend", "frontend"],
    });
  });
  it("collapses to all when every component is affected", () => {
    expect(Selection.selectAffected(["backend/x.scala", "frontend/y.ts", ".dagger/z.ts"])).toEqual({
      kind: "all",
    });
  });
  it("treats a contract change as both app components", () => {
    expect(Selection.selectAffected(["contract/openapi.yaml"])).toEqual({
      kind: "some",
      components: ["backend", "frontend"],
    });
  });
});

describe("componentsOf", () => {
  it("expands none", () => {
    expect(Selection.componentsOf({ kind: "none" })).toEqual([]);
  });
  it("expands all", () => {
    expect(Selection.componentsOf({ kind: "all" })).toEqual(["backend", "frontend", "pipeline"]);
  });
  it("expands some", () => {
    expect(Selection.componentsOf({ kind: "some", components: ["backend"] })).toEqual(["backend"]);
  });
});

describe("shouldCheckContract", () => {
  it("is true when the backend is affected", () => {
    expect(Selection.shouldCheckContract({ kind: "all" })).toBe(true);
  });
  it("is false for a frontend-only change", () => {
    expect(Selection.shouldCheckContract({ kind: "some", components: ["frontend"] })).toBe(false);
  });
  it("is false for a pipeline-only change", () => {
    expect(Selection.shouldCheckContract({ kind: "some", components: ["pipeline"] })).toBe(false);
  });
});
