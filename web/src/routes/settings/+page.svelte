<script lang="ts">
  import { createQuery, useQueryClient } from "@tanstack/svelte-query";
  import QRCode from "qrcode";
  import { api } from "#lib/api";
  import { ChevronDown, RefreshCw, Smartphone, Square } from "@lucide/svelte";
  import PluginSettings from "#lib/components/PluginSettings.svelte";
  import { toasts } from "#lib/toast.svelte";
  import type { BrowserMode, BrowserStatus, PluginInfo, PluginSource, PluginSourceMode, Settings } from "#lib/types";

  const client = useQueryClient();
  const settings = createQuery(() => ({ queryKey: ["settings"], queryFn: api.settings }));
  const status = createQuery(() => ({ queryKey: ["status"], queryFn: api.status, refetchInterval: 3000 }));
  const plugins = createQuery(() => ({
    queryKey: ["plugins"],
    queryFn: api.plugins,
    refetchInterval: () => (status.data?.building ? 2000 : false),
  }));

  const source = createQuery(() => ({
    queryKey: ["plugin-source"],
    queryFn: api.pluginSource,
    refetchInterval: () => (status.data?.building ? 2000 : false),
  }));

  const browser = createQuery(() => ({ queryKey: ["browser"], queryFn: api.browser, refetchInterval: 5000 }));

  let openPlugin = $state<string | null>(null);
  let pluginSearch = $state("");
  let pluginFilter = $state<"all" | "enabled" | "disabled" | "attention">("all");
  let enabling = $state<string[]>([]);
  let log = $state<string | null>(null);
  let mpvArgs = $state("");
  $effect(() => {
    if (settings.data) mpvArgs = settings.data.mpv_extra_args.join(" ");
  });

  async function save(patch: Partial<Settings>) {
    try {
      const s = await api.saveSettings(patch);
      client.setQueryData(["settings"], s);
      // Visibility settings change what every list shows.
      if ("show_nsfw" in patch || "disabled_plugins" in patch) client.invalidateQueries();
    } catch (e) {
      toasts.error(e);
    }
  }

  async function browserAction(fn: () => Promise<BrowserStatus>) {
    try {
      client.setQueryData(["browser"], await fn());
    } catch (e) {
      toasts.error(e);
    }
  }

  const modes: { id: BrowserMode; label: string }[] = [
    { id: "headless", label: "Headless (no window)" },
    { id: "xvfb", label: "Virtual display (Xvfb)" },
    { id: "headed", label: "Visible window" },
  ];

  /** The server stops a running browser so the next request starts it in the new mode. */
  async function setMode(mode: BrowserMode) {
    await save({ chromium_mode: mode });
    client.invalidateQueries({ queryKey: ["browser"] });
  }

  function browserState(b: BrowserStatus) {
    if (!b.executable) return "Chromium isn't installed";
    if (!b.running) return "Not running; it starts when a plugin needs it";
    return `Running ${b.mode === "headed" ? "in a visible window" : b.mode === "xvfb" ? "on a virtual display" : "headless"}`;
  }

  /** A loaded plugin that won't fully link against this runtime. */
  const linkWarning = (p: PluginInfo) =>
    p.runtimeLinkage ?? (p.linkage.length ? `${p.linkage.length} call${p.linkage.length > 1 ? "s" : ""} into the runtime won't link, e.g. ${p.linkage[0]}` : null);

  async function setEnabled(name: string, enabled: boolean) {
    enabling = [...enabling, name];
    try {
      await api.setPluginEnabled(name, enabled);
      await client.invalidateQueries();
    } catch (e) {
      toasts.error(e);
    } finally {
      enabling = enabling.filter((item) => item !== name);
    }
  }

  async function rebuild() {
    await api.rebuild().catch(toasts.error);
    toasts.show("Rebuilding plugins…");
    client.invalidateQueries({ queryKey: ["status"] });
  }

  /** The source the server loads plugins from; it unloads everything and loads from the new one. */
  async function setSource(patch: Partial<Settings>) {
    await save(patch);
    setTimeout(() => client.invalidateQueries(), 400);
  }

  // Repo mode: which action is running ("check", "all" or a plugin name), so its button shows a spinner.
  let working = $state<string | null>(null);
  async function repoAction(key: string, fn: () => Promise<PluginSource | { failed: Record<string, string>; source: PluginSource }>) {
    working = key;
    try {
      const r = await fn();
      if ("failed" in r) {
        client.setQueryData(["plugin-source"], r.source);
        const failed = Object.entries(r.failed);
        if (failed.length) toasts.error(new Error(failed.map(([n, e]) => `${n}: ${e}`).join("; ")));
      } else client.setQueryData(["plugin-source"], r);
    } catch (e) {
      toasts.error(e);
    } finally {
      working = null;
      // Updated plugins can add or drop providers.
      client.invalidateQueries();
    }
  }

  const sources: { id: PluginSourceMode; label: string }[] = [
    { id: "repo", label: "Online repo (published plugins)" },
    { id: "dev", label: "Local extensions repo (built with gradle)" },
  ];

  const ago = (t: number) => {
    const m = Math.round((Date.now() - t) / 60000);
    if (m < 1) return "just now";
    if (m < 60) return `${m} min ago`;
    if (m < 1440) return `${Math.round(m / 60)} h ago`;
    return new Date(t).toLocaleDateString();
  };

  const src = $derived(source.data);
  const repo = $derived(src?.mode === "repo");

  let repoUrl = $state("");
  $effect(() => {
    if (settings.data) repoUrl = settings.data.plugin_repo_url;
  });

  // Pairing (desktop only): QR code of the pairing link.
  let qr = $state<string | null>(null);
  let pairUrl = $state<string | null>(null);
  async function showPairing() {
    try {
      const p = await api.pairing();
      pairUrl = p.url;
      qr = await QRCode.toDataURL(p.url, { margin: 1, width: 240, color: { dark: "#0e0f13", light: "#ffffff" } });
    } catch (e) {
      toasts.error(e);
    }
  }
  async function rotate() {
    if (!confirm("Sign out every paired device? They'll need to scan a new code.")) return;
    await api.rotateToken().catch(toasts.error);
    await showPairing();
    toasts.show("All devices signed out");
  }

  const hasUpdate = (p: PluginInfo) => p.available != null && p.version != null && p.available > p.version && p.status !== "missing";

  const statusColor: Record<string, string> = { loaded: "good", error: "bad", missing: "warn", disabled: "off" };
  const needsAttention = (p: PluginInfo) => p.status === "error" || p.status === "missing" || (p.status === "loaded" && !!linkWarning(p));
  const visiblePlugins = $derived((plugins.data ?? []).filter((p) => {
    const query = pluginSearch.trim().toLocaleLowerCase();
    const matchesSearch = !query || [p.name, p.description ?? "", ...p.providers].some((value) => value.toLocaleLowerCase().includes(query));
    return matchesSearch && (pluginFilter === "all" || (pluginFilter === "enabled" && p.enabled) || (pluginFilter === "disabled" && !p.enabled) || (pluginFilter === "attention" && needsAttention(p)));
  }));
</script>

<div class="page narrow">
  <h1 class="page-title">Settings</h1>

  {#if status.data?.loopback}
    <section>
      <h2>Pair a phone</h2>
      <p class="muted">Scan with your phone's camera to use it as a remote on this network.</p>
      {#if qr}
        <div class="pair">
          <img src={qr} alt="Pairing QR code" width="200" height="200" />
          <div class="pair-text">
            <code>{pairUrl}</code>
            <button class="btn" onclick={rotate}>Sign out all devices</button>
          </div>
        </div>
      {:else}
        <button class="btn start" onclick={showPairing}><Smartphone size={18} strokeWidth={1.8} aria-hidden="true" /> Show pairing code</button>
      {/if}
    </section>
  {/if}

  {#if settings.data}
    {@const s = settings.data}
    <section>
      <h2>Playback</h2>
      <label class="toggle">
        <span><b>Auto-play the best link</b><small>Starts once {s.autoplay_min_links} links arrive or {s.autoplay_wait_ms / 1000}s after the first.</small></span>
        <input type="checkbox" checked={s.autoplay_best} onchange={(e) => save({ autoplay_best: e.currentTarget.checked })} />
      </label>
      <div class="pair-inputs">
        <label>Links to wait for <input type="number" min="1" max="20" value={s.autoplay_min_links} onchange={(e) => save({ autoplay_min_links: +e.currentTarget.value })} /></label>
        <label>Max wait (ms) <input type="number" min="0" step="500" value={s.autoplay_wait_ms} onchange={(e) => save({ autoplay_wait_ms: +e.currentTarget.value })} /></label>
      </div>
      <label class="toggle">
        <span><b>Play the next episode automatically</b></span>
        <input type="checkbox" checked={s.autoplay_next} onchange={(e) => save({ autoplay_next: e.currentTarget.checked })} />
      </label>
      <label class="toggle">
        <span><b>Send every stream through the proxy</b><small>Only needed when a site rejects mpv's direct requests.</small></span>
        <input type="checkbox" checked={s.proxy_all} onchange={(e) => save({ proxy_all: e.currentTarget.checked })} />
      </label>
      <label class="stack">
        <b>Extra mpv options</b>
        <small class="muted">Applied the next time mpv starts, e.g. <code>--profile=gpu-hq --fullscreen</code></small>
        <input value={mpvArgs} oninput={(e) => (mpvArgs = e.currentTarget.value)} onchange={() => save({ mpv_extra_args: mpvArgs.split(/\s+/).filter(Boolean) })} placeholder="--fullscreen" />
      </label>
    </section>

    <section>
      <h2>Content</h2>
      <label class="toggle">
        <span><b>Show NSFW providers</b><small>Off hides them from home, search and the plugin list. NSFW content is never added to history.</small></span>
        <input type="checkbox" checked={s.show_nsfw} onchange={(e) => save({ show_nsfw: e.currentTarget.checked })} />
      </label>
    </section>
  {/if}

  {#if settings.data && browser.data}
    {@const b = browser.data}
    <section>
      <div class="row-head">
        <h2>Browser</h2>
        {#if b.running}<button class="btn" onclick={() => browserAction(api.stopBrowser)}><Square size={16} strokeWidth={1.8} aria-hidden="true" /> Stop</button>{/if}
      </div>
      <p class="muted small">
        Chromium passes Cloudflare checks and loads pages that need a real browser. {browserState(b)}.
      </p>
      {#if b.error}<div class="p-error flush">{b.error}</div>{/if}
      <label class="stack">
        <b>Mode</b>
        <small class="muted">Cloudflare's Turnstile often refuses headless browsers. A virtual display passes more checks without a window; a visible window lets you click through them yourself. Checks that still fail show a Solve button.</small>
        <select value={settings.data.chromium_mode} onchange={(e) => setMode(e.currentTarget.value as BrowserMode)}>
          {#each modes as m (m.id)}<option value={m.id}>{m.label}</option>{/each}
        </select>
      </label>
      {#if b.clearances.length}
        <div class="stack">
          <b>Saved Cloudflare clearances</b>
          <small class="muted">Reused until the site refuses them. Forget one to solve it again.</small>
          <ul class="hosts">
            {#each b.clearances as host (host)}
              <li><span>{host}</span><button class="link-btn" onclick={() => browserAction(() => api.forgetClearance(host))}>Forget</button></li>
            {/each}
          </ul>
        </div>
      {/if}
    </section>
  {/if}

  <section>
    <div class="row-head">
      <h2>Plugins</h2>
      {#if repo}
        <button class="btn" onclick={() => repoAction("check", api.checkUpdates)} disabled={working !== null || status.data?.building}>
          {#if working === "check" || (working === null && status.data?.building)}<span class="spinner"></span> Checking…{:else}<RefreshCw size={18} strokeWidth={1.8} aria-hidden="true" /> Check for updates{/if}
        </button>
      {:else if src}
        <button class="btn" onclick={rebuild} disabled={status.data?.building || !src.devAvailable}>
          {#if status.data?.building}<span class="spinner"></span> Building…{:else}<RefreshCw size={18} strokeWidth={1.8} aria-hidden="true" /> Rebuild{/if}
        </button>
      {/if}
    </div>
    {#if settings.data && src}
      <label class="stack">
        <b>Source</b>
        {#if !status.data?.loopback}<small class="muted">Only the desktop can change where plugins come from.</small>{/if}
        <select value={src.mode} disabled={!status.data?.loopback} onchange={(e) => setSource({ plugin_source: e.currentTarget.value as PluginSourceMode })}>
          {#each sources as o (o.id)}<option value={o.id} disabled={o.id === "dev" && !src.devAvailable}>{o.label}{o.id === "dev" && !src.devAvailable ? " (not found)" : ""}</option>{/each}
        </select>
      </label>
      {#if repo}
        {#if status.data?.loopback}
          <label class="stack">
            <b>Repo URL</b>
            <small class="muted">A Cloudstream <code>repo.json</code> or <code>plugins.json</code>. Only plugins it publishes as cross-platform jars run here.</small>
            <input type="url" value={repoUrl} oninput={(e) => (repoUrl = e.currentTarget.value)} onchange={() => repoUrl.trim() && repoUrl !== settings.data?.plugin_repo_url && setSource({ plugin_repo_url: repoUrl.trim() })} />
          </label>
        {/if}
        <p class="muted small">
          {src.repoName ?? "The repo"}{src.checkedAt ? `, checked ${ago(src.checkedAt)}` : ""}.
          {#if status.data}{status.data.loaded} of {status.data.plugins} loaded.{/if}
          Downloaded jars are checked against the repo's hashes; the last two versions of each are kept for rollback.
        </p>
        {#if src.updates.length}
          <button class="btn start primary" onclick={() => repoAction("all", () => api.updatePlugins())} disabled={working !== null}>
            {#if working === "all"}<span class="spinner"></span> Updating…{:else}<ChevronDown size={18} strokeWidth={1.8} aria-hidden="true" /> Update {src.updates.length} plugin{src.updates.length > 1 ? "s" : ""}{/if}
          </button>
        {/if}
      {:else}
        <p class="muted small">
          Built from <code>{src.devPath}</code>. Any new <code>&lt;Name&gt;/build/&lt;Name&gt;.jar</code> there is reloaded
          automatically, so Rebuild (or <code>./gradlew :Name:make</code> for cross-platform plugins) is enough.
          {#if status.data}{status.data.loaded} of {status.data.plugins} loaded.{/if}
          <button class="link-btn" onclick={async () => (log = log === null ? await api.buildLog() : null)}>{log === null ? "Show build log" : "Hide build log"}</button>
        </p>
        {#if log !== null}<pre class="log">{log || "No build has run yet."}</pre>{/if}
      {/if}
      {#if src.error}<div class="p-error flush">{src.error}</div>{/if}
    {/if}

    {#if plugins.isPending}
      <p class="muted small">Loading plugins…</p>
    {:else if plugins.isError}
      <p class="p-error flush">Could not load plugins. Try refreshing the page.</p>
    {:else if !plugins.data?.length}
      <p class="muted small">No plugins found. Check the source and its settings above.</p>
    {:else}
    <div class="plugin-tools">
      <input type="search" bind:value={pluginSearch} placeholder="Search plugins or providers" aria-label="Search plugins or providers" />
      <select bind:value={pluginFilter} aria-label="Filter plugins">
        <option value="all">All plugins</option>
        <option value="enabled">Enabled</option>
        <option value="disabled">Disabled</option>
        <option value="attention">Needs attention</option>
      </select>
    </div>
    <p class="plugin-count" aria-live="polite">Showing {visiblePlugins.length} of {plugins.data.length} plugins</p>
    {#if !visiblePlugins.length}
      <div class="plugin-empty">
        <p>No plugins match your search and filter.</p>
        <button class="link-btn" onclick={() => { pluginSearch = ""; pluginFilter = "all"; }}>Clear search and filter</button>
      </div>
    {/if}
    <ul class="plugins">
      {#each visiblePlugins as p (p.name)}
        <li>
          <div class="p-row">
            <span class="dot {p.status === "loaded" && linkWarning(p) ? "warn" : statusColor[p.status]}" title={p.status}></span>
            <button class="p-name" onclick={() => (openPlugin = openPlugin === p.name ? null : p.name)} aria-expanded={openPlugin === p.name}>
              <b>{p.name}</b>
              <small class="muted"><span class="status-label">{p.status === "loaded" && linkWarning(p) ? "Loaded with warnings" : p.status}</span> · v{p.version ?? "?"}{hasUpdate(p) ? ` → v${p.available}` : ""}{p.providers.length ? ` · ${p.providers.join(", ")}` : ""}{p.nsfw ? " · NSFW" : ""}</small>
            </button>
            {#if hasUpdate(p) || (p.status === "missing" && p.available != null && p.enabled)}
              <button class="btn small-btn" onclick={() => repoAction(p.name, () => api.updatePlugins([p.name]))} disabled={working !== null}>
                {#if working === p.name}<span class="spinner"></span>{:else}{p.status === "missing" ? "Install" : "Update"}{/if}
              </button>
            {/if}
            <label class="plugin-toggle">
              <input type="checkbox" checked={p.enabled} disabled={enabling.includes(p.name)} onchange={(e) => { const checked = e.currentTarget.checked; e.currentTarget.checked = p.enabled; setEnabled(p.name, checked); }} aria-label="Enable {p.name}" />
              <span>{enabling.includes(p.name) ? "Saving…" : p.enabled ? "Enabled" : "Disabled"}</span>
            </label>
          </div>
          {#if p.error}<div class="p-error">{p.error}</div>{/if}
          {#if p.status === "loaded" && linkWarning(p)}<div class="p-warn">{linkWarning(p)}</div>{/if}
          {#if openPlugin === p.name}
            <div class="p-more">
              {#if p.description}<p class="muted small">{p.description}</p>{/if}
              {#if p.linkage.length > 1}
                <pre class="log">{p.linkage.join("\n")}</pre>
              {/if}
              {#if p.previous != null}
                <button class="link-btn" onclick={() => repoAction(p.name, () => api.rollbackPlugin(p.name))} disabled={working !== null}>Roll back to v{p.previous}</button>
              {/if}
              {#if p.status === "loaded"}<PluginSettings name={p.name} />{/if}
            </div>
          {/if}
        </li>
      {/each}
    </ul>
    {/if}
  </section>
</div>

<style>
  .narrow {
    max-width: 760px;
  }
  section {
    background: var(--surface);
    border: 1px solid var(--line);
    border-radius: 16px;
    padding: 16px;
    margin-bottom: 16px;
    display: flex;
    flex-direction: column;
    gap: 12px;
  }
  h2 {
    font-size: 17px;
  }
  section > p {
    margin: 0;
  }
  .small {
    font-size: 13.5px;
  }
  .toggle {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    cursor: pointer;
  }
  .toggle span,
  .stack {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }
  small {
    color: var(--muted);
    font-size: 13px;
  }
  input[type="checkbox"] {
    appearance: none;
    width: 46px;
    height: 28px;
    border-radius: 999px;
    background: var(--surface-3);
    position: relative;
    flex-shrink: 0;
    cursor: pointer;
    padding: 0;
    border: 0;
    transition: background 0.15s;
  }
  input[type="checkbox"]::after {
    content: "";
    position: absolute;
    top: 3px;
    left: 3px;
    width: 22px;
    height: 22px;
    border-radius: 50%;
    background: #fff;
    transition: transform 0.15s;
  }
  input[type="checkbox"]:checked {
    background: var(--accent);
  }
  input[type="checkbox"]:checked::after {
    transform: translateX(18px);
  }
  .pair-inputs {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 10px;
  }
  .pair-inputs label {
    display: flex;
    flex-direction: column;
    gap: 4px;
    font-size: 13px;
    color: var(--muted);
  }
  .pair {
    display: flex;
    gap: 16px;
    align-items: center;
    flex-wrap: wrap;
  }
  .pair img {
    border-radius: 12px;
    background: #fff;
  }
  .pair-text {
    display: flex;
    flex-direction: column;
    gap: 10px;
    min-width: 0;
    flex: 1;
  }
  code {
    font-size: 12.5px;
    word-break: break-all;
    color: var(--muted);
  }
  .start {
    align-self: flex-start;
  }
  .row-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }
  .link-btn {
    color: var(--accent);
    font-weight: 600;
  }
  .link-btn:disabled {
    opacity: 0.5;
  }
  .small-btn {
    padding: 6px 12px;
    font-size: 13px;
    flex-shrink: 0;
  }
  .log {
    max-height: 320px;
    overflow: auto;
    font-size: 11.5px;
    background: var(--bg);
    border-radius: 8px;
    padding: 10px;
    white-space: pre-wrap;
    margin: 0;
  }
  .plugins {
    list-style: none;
    margin: 0;
    padding: 0;
  }
  .plugin-tools {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    margin-top: 20px;
  }
  .plugin-tools input { flex: 1 1 220px; min-width: 0; }
  .plugin-tools select { flex: 0 1 175px; }
  .plugin-count { margin: 10px 0; color: var(--muted); font-size: 12.5px; }
  .plugin-empty { padding: 12px 0 18px; }
  .plugin-empty p { margin: 0 0 6px; }
  .plugin-toggle { display: flex; align-items: center; gap: 7px; flex-shrink: 0; font-size: 12px; color: var(--muted); }
  .status-label { text-transform: capitalize; }
  @media (max-width: 480px) {
    .plugin-tools select { flex: 1 1 100%; }
    .plugin-toggle span { display: none; }
  }
  .plugins li {
    border-top: 1px solid var(--line);
    padding: 8px 0;
  }
  .p-row {
    display: flex;
    align-items: center;
    gap: 12px;
  }
  .p-name {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    text-align: left;
    padding: 4px 0;
  }
  .p-name small {
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .dot {
    width: 9px;
    height: 9px;
    border-radius: 50%;
    flex-shrink: 0;
  }
  .dot.good {
    background: var(--good);
  }
  .dot.bad {
    background: var(--danger);
  }
  .dot.warn {
    background: #f2c14e;
  }
  .dot.off {
    background: var(--surface-3);
  }
  .p-error {
    margin: 4px 0 0 21px;
    font-size: 12.5px;
    color: var(--danger);
    word-break: break-word;
  }
  .p-more {
    margin: 6px 0 4px 21px;
  }
  .p-warn {
    margin: 4px 0 0 21px;
    font-size: 12.5px;
    color: #f2c14e;
    word-break: break-word;
  }
  .p-error.flush {
    margin: 0;
  }
  .hosts {
    list-style: none;
    margin: 4px 0 0;
    padding: 0;
  }
  .hosts li {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 6px 0;
    border-top: 1px solid var(--line);
    font-size: 14px;
  }
</style>
