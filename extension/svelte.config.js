// Read by vite-plugin-svelte (through @wxt-dev/module-svelte) and svelte-check, which would otherwise look in
// vite.config.ts (only there for `vp fmt`/`vp lint`). Runes everywhere, as in web/.
export default {
  compilerOptions: { runes: true },
};
