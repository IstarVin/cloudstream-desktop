import adapter from "@sveltejs/adapter-static";
import { sveltekit } from "@sveltejs/kit/vite";
import { defineConfig, lazyPlugins } from "vite-plus";

// The Ktor server. In development `vp dev` proxies the API to it; in production Ktor serves the build.
const server = "http://127.0.0.1:6969";

export default defineConfig({
  build: {
    // The HLS and DASH playback engines are large, dynamically loaded dependencies.
    chunkSizeWarningLimit: 900,
  },
  fmt: {
    svelte: true,
  },
  lint: {
    jsPlugins: [{ name: "vite-plus", specifier: "vite-plus/oxlint-plugin" }],
    rules: { "vite-plus/prefer-vite-plus-imports": "error" },
    options: { typeAware: true, typeCheck: true },
  },
  server: {
    proxy: {
      "/api": { target: server, ws: true },
      "/proxy": server,
    },
  },
  plugins: lazyPlugins(() => [
    sveltekit({
      compilerOptions: {
        // Force runes mode for the project, except for libraries. Can be removed in svelte 6.
        runes: ({ filename }) =>
          filename.split(/[/\\]/).includes("node_modules") ? undefined : true,
      },
      // A static SPA: Ktor serves these files and falls back to index.html for client-side routes.
      adapter: adapter({
        pages: "../server/src/main/resources/web",
        assets: "../server/src/main/resources/web",
        fallback: "index.html",
        strict: false,
      }),
    }),
  ]),
});
