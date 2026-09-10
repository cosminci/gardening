import { fileURLToPath } from "node:url";
import { defineConfig } from "vite";
import solid from "vite-plugin-solid";

export default defineConfig({
  plugins: [solid()],
  resolve: {
    alias: {
      "@contract": fileURLToPath(new URL("../contract/generated/api.ts", import.meta.url)),
    },
  },
  build: {
    outDir: "dist",
    target: "es2023",
  },
});
