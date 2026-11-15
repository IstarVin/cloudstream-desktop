import { resolve } from "node:path";
import { defineConfig } from "wxt";

export default defineConfig({
  srcDir: "src",
  // MV3 for Firefox too, so both browsers share `action`, the badge and the event-page background.
  manifestVersion: 3,
  modules: ["@wxt-dev/module-svelte"],
  // Explicit imports only: the code reads the same in the editor, in tests and in the build.
  imports: false,
  alias: {
    // The web UI's API types (web/src/lib/types.ts), shared rather than copied.
    $shared: resolve(import.meta.dirname, "../web/src/lib"),
  },
  manifest: ({ browser }) => ({
    name: "Cloudstream",
    description: "Open and play titles from provider sites in your Cloudstream Desktop server.",
    // No "tabs": host access already exposes tab URLs, and "tabs" adds a browsing-history install warning.
    permissions: ["storage", "contextMenus", "alarms", "notifications"],
    host_permissions: ["<all_urls>"],
    action: { default_title: "Cloudstream" },
    ...(browser === "firefox" && {
      browser_specific_settings: {
        gecko: {
          id: "cloudstream-extension@istarvin.uk",
          strict_min_version: "140.0",
          // Everything goes to the user's own server; nothing is collected.
          data_collection_permissions: { required: ["none"] },
        },
        gecko_android: { strict_min_version: "142.0" },
      },
    }),
  }),
});
