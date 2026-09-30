import { fileURLToPath } from "node:url";
import { defineConfig } from "vite";
import type { Plugin } from "vite";
import solid from "vite-plugin-solid";

const backendPort = process.env["GARDENING_PORT"] ?? "8080";
const host = process.env["GARDENING_HOST"] ?? "0.0.0.0";

// local.py builds the frontend in watch mode with GARDENING_LIVE_RELOAD set. This
// stamps each build into the served index.html and injects a poller that reloads
// the page once a rebuild changes the stamp, so backend-served edits appear without
// a manual refresh. Production builds omit the plugin entirely.
const liveReload = (): Plugin => {
  let stamp = "";
  return {
    name: "gardening-live-reload",
    apply: "build",
    buildStart() {
      stamp = Date.now().toString(36);
    },
    transformIndexHtml() {
      return [
        { tag: "meta", attrs: { name: "build-stamp", content: stamp }, injectTo: "head" },
        {
          tag: "script",
          injectTo: "body",
          children: `(() => {
  const meta = document.querySelector('meta[name="build-stamp"]');
  const current = meta ? meta.getAttribute("content") : null;
  const poll = async () => {
    try {
      const response = await fetch(location.pathname, { cache: "no-store" });
      const match = (await response.text()).match(/name="build-stamp" content="([^"]+)"/);
      if (match && match[1] !== current) {
        location.reload();
        return;
      }
    } catch {
      // Backend is briefly unavailable mid-rebuild; keep polling.
    }
    window.setTimeout(poll, 1000);
  };
  window.setTimeout(poll, 1000);
})();`,
        },
      ];
    },
  };
};

export default defineConfig({
  plugins: [solid(), process.env["GARDENING_LIVE_RELOAD"] ? liveReload() : false],
  server: {
    host,
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
        ].map((path) => [path, { target: `http://127.0.0.1:${backendPort}` }]),
      ),
      "/attention/feed": {
        target: `ws://127.0.0.1:${backendPort}`,
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
