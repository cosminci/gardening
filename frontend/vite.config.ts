import { fileURLToPath } from "node:url";
import { defineConfig } from "vite";
import solid from "vite-plugin-solid";

export default defineConfig({
  plugins: [solid()],
  server: {
    host: "127.0.0.1",
    strictPort: true,
    proxy: {
      ...Object.fromEntries(
        [
          "/health",
          "/plants",
          "/attention",
          "/operations",
          "/pesticides",
          "/substrate",
          "/photos",
        ].map((path) => [
          path,
          { target: `http://127.0.0.1:${process.env["GARDENING_PORT"] ?? "8080"}` },
        ]),
      ),
      "/attention/feed": {
        target: `ws://127.0.0.1:${process.env["GARDENING_PORT"] ?? "8080"}`,
        ws: true,
      },
    },
  },
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
