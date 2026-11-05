import { svelte } from "@sveltejs/vite-plugin-svelte";
import { defineConfig } from "vite-plus";

// `vp dev` serves the portal and proxies the API to a local `wrangler dev` Worker.
export default defineConfig({
  root: "web",
  build: { outDir: "../dist", emptyOutDir: true },
  fmt: { svelte: true },
  lint: {
    jsPlugins: [{ name: "vite-plus", specifier: "vite-plus/oxlint-plugin" }],
    rules: { "vite-plus/prefer-vite-plus-imports": "error" },
  },
  server: { proxy: { "/api": "http://127.0.0.1:8787" } },
  plugins: [
    svelte({
      compilerOptions: {
        runes: ({ filename }) =>
          filename.split(/[/\\]/).includes("node_modules") ? undefined : true,
      },
    }),
  ],
});
