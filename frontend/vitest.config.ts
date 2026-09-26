import { fileURLToPath } from "node:url";
import solid from "vite-plugin-solid";
import { defineConfig } from "vitest/config";

export default defineConfig({
  // Disable solid's HMR wrapper under test: it is dev-server-only and its refresh scaffolding
  // adds phantom branches to coverage that no test can (or should) exercise.
  plugins: [solid({ hot: false })],
  resolve: {
    alias: {
      "@contract": fileURLToPath(new URL("../contract/generated/api.ts", import.meta.url)),
    },
    conditions: ["development", "browser"],
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./test/setup.ts"],
    include: ["test/**/*.componentTest.{ts,tsx}", "test/**/*.seamIntegrationTest.{ts,tsx}"],
    coverage: {
      provider: "v8",
      include: ["src/**/*.{ts,tsx}"],
      exclude: ["src/main.tsx"],
      thresholds: {
        statements: 100,
        branches: 100,
        functions: 100,
        lines: 100,
      },
    },
  },
});
