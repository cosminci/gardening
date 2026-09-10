/** @type {import('dependency-cruiser').IConfiguration} */
module.exports = {
  forbidden: [
    {
      name: "domain-stays-pure",
      comment: "The domain must not depend on adapters or the app composition root.",
      severity: "error",
      from: { path: "^src/domain/" },
      to: { path: "^src/(adapters|app)/" },
    },
    {
      name: "adapters-do-not-import-app",
      comment: "Adapters must not depend on the composition root.",
      severity: "error",
      from: { path: "^src/adapters/" },
      to: { path: "^src/(app/|main\\.tsx$)" },
    },
    {
      name: "no-circular",
      comment: "No circular dependencies.",
      severity: "error",
      from: {},
      to: { circular: true },
    },
    {
      name: "no-orphans",
      comment: "No orphan modules (the entrypoint is exempt).",
      severity: "warn",
      from: { orphan: true, pathNot: ["\\.d\\.ts$", "(^|/)main\\.tsx$"] },
      to: {},
    },
  ],
  options: {
    doNotFollow: { path: "node_modules" },
    tsConfig: { fileName: "tsconfig.json" },
    tsPreCompilationDeps: true,
    enhancedResolveOptions: { extensions: [".ts", ".tsx"] },
  },
};
