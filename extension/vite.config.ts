// Only for `vp fmt` and `vp lint`, matching web/ and auth-worker/. WXT builds with its own Vite config (wxt.config.ts).
import { defineConfig } from "vite-plus";

export default defineConfig({
  fmt: { svelte: true },
});
