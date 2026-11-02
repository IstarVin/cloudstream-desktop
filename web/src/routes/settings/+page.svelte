<script lang="ts">
  import { page } from "$app/state";
  import { api } from "#lib/api";
  import { cast } from "#lib/cast.svelte";
  import PluginSettings from "#lib/components/PluginSettings.svelte";
  import SourceProfiles from "#lib/components/SourceProfiles.svelte";
  import { PLAY_TARGETS, playTarget, type PlayTarget } from "#lib/play-target.svelte";
  import { prefs } from "#lib/player/prefs.svelte";
  import { toasts } from "#lib/toast.svelte";
  import type {
    BrowserMode,
    BrowserStatus,
    PluginInfo,
    PluginSource,
    PluginSourceMode,
    Settings,
    SkipMode,
  } from "#lib/types";
  import {
    Check,
    CircleAlert,
    ChevronDown,
    Globe,
    Layers,
    ListOrdered,
    Play,
    Puzzle,
    Search,
    RefreshCw,
    Shield,
    Smartphone,
    Square,
  } from "@lucide/svelte";
  import { createQuery, useQueryClient } from "@tanstack/svelte-query";
  import QRCode from "qrcode";
  import { onDestroy } from "svelte";

  const client = useQueryClient();
  const settings = createQuery(() => ({
    queryKey: ["settings"],
    queryFn: api.settings,
  }));
  const status = createQuery(() => ({
    queryKey: ["status"],
    queryFn: api.status,
    refetchInterval: 3000,
  }));
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

  const browser = createQuery(() => ({
    queryKey: ["browser"],
    queryFn: api.browser,
    refetchInterval: 5000,
  }));

  const categories = [
    { id: "playback", label: "Playback", icon: Play },
    { id: "priority", label: "Source priority", icon: ListOrdered },
    { id: "content", label: "Content", icon: Shield },
    { id: "browser", label: "Browser", icon: Globe },
    { id: "plugins", label: "Plugins", icon: Layers },
    { id: "devices", label: "Pair a phone", icon: Smartphone },
  ];
  const availableCategories = $derived(
    categories.filter((category) => category.id !== "devices" || status.data?.loopback),
  );
  const activeCategory = $derived(
    availableCategories.find((category) => `#settings-${category.id}` === page.url.hash)?.id ??
      "playback",
  );
  let pendingSaves = $state(0);
  let saveState = $state<"idle" | "saved" | "error">("idle");
  let saveFailed = false;

  let openPlugin = $state<string | null>(null);
  let pluginSearch = $state("");
  let pluginFilter = $state<"all" | "enabled" | "disabled" | "attention">("all");
  // Keep the requested state until the runtime has actually loaded or unloaded the plugin.
  let pluginChanges = $state<Record<string, boolean>>({});
  let pluginErrors = $state<Record<string, string>>({});
  let failedIcons = $state<Record<string, string>>({});
  const changingPlugins = $derived(Object.keys(pluginChanges).length > 0);
  let disposed = false;
  onDestroy(() => {
    disposed = true;
  });
  let log = $state<string | null>(null);
  let mpvArgs = $derived(settings.data?.mpv_extra_args.join(" ") ?? "");

  async function save(patch: Partial<Settings>) {
    if (!pendingSaves) saveFailed = false;
    pendingSaves += 1;
    saveState = "idle";
    try {
      const s = await api.saveSettings(patch);
      client.setQueryData(["settings"], s);
      // Visibility settings change what every list shows.
      if ("show_nsfw" in patch || "disabled_plugins" in patch) client.invalidateQueries();
    } catch (e) {
      saveFailed = true;
      toasts.error(e);
    } finally {
      pendingSaves -= 1;
      if (!pendingSaves) saveState = saveFailed ? "error" : "saved";
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
    p.runtimeLinkage ??
    (p.linkage.length
      ? `${p.linkage.length} call${p.linkage.length > 1 ? "s" : ""} into the runtime won't link, e.g. ${p.linkage[0]}`
      : null);

  async function setEnabled(name: string, enabled: boolean) {
    if (pluginChanges[name] !== undefined) return;
    pluginChanges[name] = enabled;
    delete pluginErrors[name];
    try {
      // This saves the preference; the host loads/unloads asynchronously after it returns.
      await api.setPluginEnabled(name, enabled);
      const deadline = Date.now() + 120_000;
      while (!disposed && Date.now() < deadline) {
        const [list, host] = await Promise.all([api.plugins(), api.status()]);
        if (disposed) return;
        client.setQueryData(["plugins"], list);
        client.setQueryData(["status"], host);
        const plugin = list.find((p) => p.name === name);
        if (!plugin)
          throw new Error(`${name} is no longer in this source. Refresh the plugin list.`);
        const settled =
          plugin.enabled === enabled &&
          (enabled
            ? plugin.status === "loaded" ||
              plugin.status === "error" ||
              (plugin.status === "missing" && !host.building)
            : plugin.status === "disabled");
        if (settled) {
          if (enabled && plugin.status !== "loaded") {
            throw new Error(
              plugin.error ?? `${name} could not be loaded. Check its details and try again.`,
            );
          }
          toasts.show(`${name} ${enabled ? "enabled" : "disabled"}`);
          return;
        }
        await new Promise((resolve) => setTimeout(resolve, 750));
      }
      if (!disposed)
        throw new Error(
          `${name} is taking longer than expected. Refresh the plugin list to check its status.`,
        );
    } catch (e) {
      if (!disposed) {
        pluginErrors[name] = e instanceof Error ? e.message : String(e);
        toasts.error(e);
      }
    } finally {
      if (!disposed) {
        delete pluginChanges[name];
        // Provider lists and settings must reflect the completed operation too.
        client.invalidateQueries();
      }
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
  async function repoAction(
    key: string,
    fn: () => Promise<PluginSource | { failed: Record<string, string>; source: PluginSource }>,
  ) {
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

  let repoUrl = $derived(settings.data?.plugin_repo_url ?? "");

  // Pairing (desktop only): QR code of the pairing link.
  let qr = $state<string | null>(null);
  let pairUrl = $state<string | null>(null);
  async function showPairing() {
    try {
      const p = await api.pairing();
      pairUrl = p.url;
      qr = await QRCode.toDataURL(p.url, {
        margin: 1,
        width: 240,
        color: { dark: "#0e0f13", light: "#ffffff" },
      });
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

  const hasUpdate = (p: PluginInfo) =>
    p.available != null && p.version != null && p.available > p.version && p.status !== "missing";

  const needsAttention = (p: PluginInfo) =>
    p.status === "error" || p.status === "missing" || (p.status === "loaded" && !!linkWarning(p));
  const visiblePlugins = $derived(
    (plugins.data ?? []).filter((p) => {
      const query = pluginSearch.trim().toLocaleLowerCase();
      const matchesSearch =
        !query ||
        [p.name, p.description ?? "", ...p.providers].some((value) =>
          value.toLocaleLowerCase().includes(query),
        );
      return (
        matchesSearch &&
        (pluginChanges[p.name] !== undefined ||
          pluginFilter === "all" ||
          (pluginFilter === "enabled" && p.enabled) ||
          (pluginFilter === "disabled" && !p.enabled) ||
          (pluginFilter === "attention" && needsAttention(p)))
      );
    }),
  );
</script>

<svelte:head><title>Settings · Cloudstream</title></svelte:head>

<div class="page settings-page">
  <header class="settings-heading">
    <div>
      <h1 class="page-title">Settings</h1>
      <p>Make Cloudstream work your way.</p>
    </div>
    {#if activeCategory !== "priority" && activeCategory !== "devices"}
      <p class={["save-status", { failed: saveState === "error" }]} role="status">
        {#if pendingSaves}<span class="spinner"></span> Saving…
        {:else if saveState === "error"}Could not save. Try again.
        {:else if saveState === "saved"}<Check size={15} aria-hidden="true" /> Changes saved
        {:else}Changes save automatically{/if}
      </p>
    {/if}
  </header>

  <div class="settings-layout">
    <nav class="settings-nav" aria-label="Settings categories">
      {#each availableCategories as category (category.id)}
        <a
          href="#settings-{category.id}"
          data-sveltekit-reset="false"
          class={{ selected: activeCategory === category.id }}
          aria-current={activeCategory === category.id ? "location" : undefined}
        >
          <category.icon size={18} strokeWidth={1.8} aria-hidden="true" />
          <span>{category.label}</span>
        </a>
      {/each}
    </nav>
    <div class="settings-content">
      {#if settings.isPending}
        <div class="loading-settings" role="status">
          <span class="sr-only">Loading settings…</span>
          <div></div>
          <div></div>
          <div></div>
        </div>
      {:else if settings.isError}
        <div class="load-error" role="alert">
          <h2>Could not load settings</h2>
          <p>Check your connection to the desktop and try again.</p>
          <button class="btn" onclick={() => settings.refetch()}>Try again</button>
        </div>
      {/if}

      {#if status.data?.loopback}
        <section hidden={activeCategory !== "devices"} aria-labelledby="devices-heading">
          <h2 id="devices-heading">Pair a phone</h2>
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
            <button class="btn start" onclick={showPairing}
              ><Smartphone size={18} strokeWidth={1.8} aria-hidden="true" /> Show pairing code</button
            >
          {/if}
        </section>
      {/if}

      {#if settings.data}
        {@const s = settings.data}
        <section hidden={activeCategory !== "playback"} aria-labelledby="playback-heading">
          <div class="section-heading">
            <h2 id="playback-heading">Playback</h2>
            <p>Choose how streams start and what happens next.</p>
          </div>
          <h3>Player on this device</h3>
          <label class="setting-row">
            <span class="setting-copy"
              ><b>Play episodes on</b>
              <small class="muted"
                >Saved in this browser only. You can still switch for one episode in the sources
                panel.{status.data && !status.data.mpvAvailable
                  ? " mpv isn't available on this server, so episodes play in the browser."
                  : ""}</small
              ></span
            >
            <select
              value={playTarget.default}
              onchange={(e) => playTarget.setDefault(e.currentTarget.value as PlayTarget)}
            >
              {#each PLAY_TARGETS as t (t.id)}
                <option
                  value={t.id}
                  disabled={t.id === "mpv" && status.data != null && !status.data.mpvAvailable}
                  >{t.label}</option
                >
              {/each}
            </select>
          </label>
          <h3>Casting</h3>
          <label class="setting-row">
            <span class="setting-copy"
              ><b>This device's name</b>
              <small class="muted">How it shows in other devices' Play on list.</small></span
            >
            <input
              type="text"
              maxlength="60"
              value={cast.name}
              onchange={(e) => cast.rename(e.currentTarget.value)}
            />
          </label>
          <label class="toggle">
            <span
              ><b>Allow casting to this browser</b><small
                >Other paired devices can play here, and control it, while this tab is open.</small
              ></span
            >
            <input
              type="checkbox"
              role="switch"
              checked={cast.receiver}
              onchange={(e) => cast.setReceiver(e.currentTarget.checked)}
            />
          </label>
          <details class="advanced">
            <summary>Browser player <ChevronDown size={17} aria-hidden="true" /></summary>
            <div class="advanced-fields">
              <label class="setting-row">
                <span class="setting-copy"><b>Double-tap and arrow-key seek</b></span>
                <select
                  value={String(prefs.value.seekStep)}
                  onchange={(e) => prefs.update({ seekStep: Number(e.currentTarget.value) })}
                >
                  {#each [5, 10, 15, 30] as n (n)}<option value={String(n)}>{n} seconds</option
                    >{/each}
                </select>
              </label>
              <label class="setting-row">
                <span class="setting-copy"><b>Speed while holding the video</b></span>
                <select
                  value={String(prefs.value.holdSpeed)}
                  onchange={(e) => prefs.update({ holdSpeed: Number(e.currentTarget.value) })}
                >
                  {#each [1.5, 2, 3] as n (n)}<option value={String(n)}>{n}×</option>{/each}
                </select>
              </label>
              <label class="toggle">
                <span
                  ><b>Swipe sideways to seek</b><small>Drag across the video to scrub.</small></span
                >
                <input
                  type="checkbox"
                  role="switch"
                  checked={prefs.value.swipeSeek}
                  onchange={(e) => prefs.update({ swipeSeek: e.currentTarget.checked })}
                />
              </label>
              <label class="toggle">
                <span
                  ><b>Swipe up and down for volume and brightness</b><small
                    >Touch screens: right half for volume, left half for brightness.</small
                  ></span
                >
                <input
                  type="checkbox"
                  role="switch"
                  checked={prefs.value.volumeGesture && prefs.value.brightnessGesture}
                  onchange={(e) =>
                    prefs.update({
                      volumeGesture: e.currentTarget.checked,
                      brightnessGesture: e.currentTarget.checked,
                    })}
                />
              </label>
              <label class="setting-row">
                <span class="setting-copy"><b>Subtitle size</b></span>
                <select
                  value={String(prefs.value.subtitleScale)}
                  onchange={(e) => prefs.update({ subtitleScale: Number(e.currentTarget.value) })}
                >
                  {#each [0.75, 0.9, 1, 1.15, 1.3, 1.5] as n (n)}<option value={String(n)}
                      >{Math.round(n * 100)}%</option
                    >{/each}
                </select>
              </label>
              <label class="toggle">
                <span
                  ><b>Subtitles on a dark box</b><small
                    >Instead of an outline. ASS subtitles keep their own styling.</small
                  ></span
                >
                <input
                  type="checkbox"
                  role="switch"
                  checked={prefs.value.subtitleBackground}
                  onchange={(e) => prefs.update({ subtitleBackground: e.currentTarget.checked })}
                />
              </label>
              <label class="toggle">
                <span
                  ><b>Picture-in-picture when leaving the tab</b><small
                    >Keeps the video playing in a small window, where the browser supports it.</small
                  ></span
                >
                <input
                  type="checkbox"
                  role="switch"
                  checked={prefs.value.autoPip}
                  onchange={(e) => prefs.update({ autoPip: e.currentTarget.checked })}
                />
              </label>
            </div>
          </details>
          <h3>Auto-play</h3>
          <label class="toggle">
            <span
              ><b>Auto-play the best link</b><small
                >Starts as soon as a link has priority 10 or more; the rest keep loading in the
                background.</small
              ></span
            >
            <input
              type="checkbox"
              role="switch"
              checked={s.autoplay_best}
              onchange={(e) => save({ autoplay_best: e.currentTarget.checked })}
            />
          </label>
          <label class="toggle">
            <span><b>Play the next episode automatically</b></span>
            <input
              type="checkbox"
              role="switch"
              checked={s.autoplay_next}
              onchange={(e) => save({ autoplay_next: e.currentTarget.checked })}
            />
          </label>
          <h3>Episode segments</h3>
          <label class="setting-row">
            <span class="setting-copy"
              ><b>Skip anime openings and endings</b>
              <small class="muted"
                >Times come from AniSkip, for providers that give the MyAnimeList or AniList id.
                They also show as chapters in mpv.</small
              ></span
            >
            <select
              value={s.skip_segments}
              onchange={(e) => save({ skip_segments: e.currentTarget.value as SkipMode })}
            >
              <option value="prompt">Offer a Skip button</option>
              <option value="auto">Skip automatically</option>
              <option value="off">Off</option>
            </select>
          </label>
          <details class="advanced">
            <summary>Advanced playback <ChevronDown size={17} aria-hidden="true" /></summary>
            <div class="advanced-fields">
              <label class="toggle">
                <span
                  ><b>Send every stream through the proxy</b><small
                    >Only needed when a site rejects mpv's direct requests.</small
                  ></span
                >
                <input
                  type="checkbox"
                  role="switch"
                  checked={s.proxy_all}
                  onchange={(e) => save({ proxy_all: e.currentTarget.checked })}
                />
              </label>
              <label class="stack">
                <b>Extra mpv options</b>
                <small class="muted"
                  >Applied the next time mpv starts, e.g. <code>--profile=gpu-hq --fullscreen</code
                  ></small
                >
                <input
                  value={mpvArgs}
                  oninput={(e) => (mpvArgs = e.currentTarget.value)}
                  onchange={() => save({ mpv_extra_args: mpvArgs.split(/\s+/).filter(Boolean) })}
                  placeholder="--fullscreen"
                  spellcheck="false"
                />
              </label>
            </div>
          </details>
        </section>

        <section hidden={activeCategory !== "priority"} aria-labelledby="priority-heading">
          <div class="section-heading">
            <h2 id="priority-heading">Source priority</h2>
            <p>Set which sources and video qualities play first.</p>
          </div>
          <SourceProfiles settings={s} />
        </section>

        <section hidden={activeCategory !== "content"} aria-labelledby="content-heading">
          <div class="section-heading">
            <h2 id="content-heading">Content</h2>
            <p>Choose which providers appear in Cloudstream.</p>
          </div>
          <label class="toggle">
            <span
              ><b>Show NSFW providers</b><small
                >Off hides them from home, search and the plugin list. NSFW content is never added
                to history.</small
              ></span
            >
            <input
              type="checkbox"
              role="switch"
              checked={s.show_nsfw}
              onchange={(e) => save({ show_nsfw: e.currentTarget.checked })}
            />
          </label>
        </section>
      {/if}

      {#if activeCategory === "browser" && browser.isPending}
        <p class="muted" role="status">Loading browser status…</p>
      {:else if activeCategory === "browser" && browser.isError}
        <div class="load-error" role="alert">
          <h2>Could not load browser status</h2>
          <p>Check your connection and try again.</p>
          <button class="btn" onclick={() => browser.refetch()}>Try again</button>
        </div>
      {/if}
      {#if settings.data && browser.data}
        {@const b = browser.data}
        <section hidden={activeCategory !== "browser"} aria-labelledby="browser-heading">
          <div class="row-head">
            <div class="section-heading">
              <h2 id="browser-heading">Browser</h2>
              <p>Handle sites that need a real browser.</p>
            </div>
            {#if b.running}<button class="btn" onclick={() => browserAction(api.stopBrowser)}
                ><Square size={16} strokeWidth={1.8} aria-hidden="true" /> Stop</button
              >{/if}
          </div>
          <p class="muted small">
            Chromium passes Cloudflare checks and loads pages that need a real browser. {browserState(
              b,
            )}.
          </p>
          {#if b.error}<div class="p-error flush">{b.error}</div>{/if}
          <label class="stack">
            <b>Mode</b>
            <small class="muted"
              >Cloudflare's Turnstile often refuses headless browsers. A virtual display passes more
              checks without a window; a visible window lets you click through them yourself. Checks
              that still fail show a Solve button.</small
            >
            <select
              value={settings.data.chromium_mode}
              onchange={(e) => setMode(e.currentTarget.value as BrowserMode)}
            >
              {#each modes as m (m.id)}<option value={m.id}>{m.label}</option>{/each}
            </select>
          </label>
          {#if b.clearances.length}
            <div class="stack">
              <b>Saved Cloudflare clearances</b>
              <small class="muted"
                >Reused until the site refuses them. Forget one to solve it again.</small
              >
              <ul class="hosts">
                {#each b.clearances as host (host)}
                  <li>
                    <span>{host}</span><button
                      class="link-btn"
                      onclick={() => browserAction(() => api.forgetClearance(host))}>Forget</button
                    >
                  </li>
                {/each}
              </ul>
            </div>
          {/if}
        </section>
      {/if}

      <section hidden={activeCategory !== "plugins"} aria-labelledby="plugins-heading">
        <div class="row-head">
          <div class="section-heading">
            <h2 id="plugins-heading">Plugins</h2>
            <p>Manage the plugins that bring your providers to Cloudstream.</p>
          </div>
          {#if repo}
            <button
              class="btn"
              onclick={() => repoAction("check", api.checkUpdates)}
              disabled={working !== null || changingPlugins || status.data?.building}
            >
              {#if working === "check" || (working === null && status.data?.building)}<span
                  class="spinner"
                ></span> Checking…{:else}<RefreshCw
                  size={18}
                  strokeWidth={1.8}
                  aria-hidden="true"
                /> Check for updates{/if}
            </button>
          {:else if src}
            <button
              class="btn"
              onclick={rebuild}
              disabled={status.data?.building || changingPlugins || !src.devAvailable}
            >
              {#if status.data?.building}<span class="spinner"></span> Building…{:else}<RefreshCw
                  size={18}
                  strokeWidth={1.8}
                  aria-hidden="true"
                /> Rebuild{/if}
            </button>
          {/if}
        </div>
        {#if settings.data && src}
          <div class="plugin-source">
            <label class="stack">
              <b>Plugin source</b>
              {#if !status.data?.loopback}<small class="muted"
                  >Only the desktop can change where plugins come from.</small
                >{/if}
              <select
                value={src.mode}
                disabled={!status.data?.loopback ||
                  changingPlugins ||
                  working !== null ||
                  status.data?.building}
                onchange={(e) =>
                  setSource({
                    plugin_source: e.currentTarget.value as PluginSourceMode,
                  })}
              >
                {#each sources as o (o.id)}<option
                    value={o.id}
                    disabled={o.id === "dev" && !src.devAvailable}
                    >{o.label}{o.id === "dev" && !src.devAvailable ? " (not found)" : ""}</option
                  >{/each}
              </select>
            </label>
            <details class="source-details">
              <summary
                >{repo ? (src.repoName ?? "Repository details") : "Local build details"}<ChevronDown
                  size={16}
                  aria-hidden="true"
                /></summary
              >
              {#if repo}
                {#if status.data?.loopback}
                  <label class="stack">
                    <b>Repo URL</b>
                    <small class="muted"
                      >A Cloudstream <code>repo.json</code> or
                      <code>plugins.json</code>. Only plugins it publishes as cross-platform jars
                      run here.</small
                    >
                    <input
                      type="url"
                      disabled={changingPlugins || working !== null || status.data?.building}
                      value={repoUrl}
                      oninput={(e) => (repoUrl = e.currentTarget.value)}
                      onchange={() =>
                        repoUrl.trim() &&
                        repoUrl !== settings.data?.plugin_repo_url &&
                        setSource({ plugin_repo_url: repoUrl.trim() })}
                    />
                  </label>
                {/if}
                <p class="muted small">
                  {src.repoName ?? "The repo"}{src.checkedAt
                    ? `, checked ${ago(src.checkedAt)}`
                    : ""}.
                  {#if status.data}{status.data.loaded} of {status.data.plugins} loaded.{/if}
                  Downloaded jars are checked against the repo's hashes; the last two versions of each
                  are kept for rollback.
                </p>
              {:else}
                <p class="muted small">
                  Built from <code>{src.devPath}</code>. Any new
                  <code>&lt;Name&gt;/build/&lt;Name&gt;.jar</code>
                  there is reloaded automatically, so Rebuild (or
                  <code>./gradlew :Name:make</code>
                  for cross-platform plugins) is enough.
                  {#if status.data}{status.data.loaded} of {status.data.plugins} loaded.{/if}
                  <button
                    class="link-btn"
                    onclick={async () => (log = log === null ? await api.buildLog() : null)}
                    >{log === null ? "Show build log" : "Hide build log"}</button
                  >
                </p>
                {#if log !== null}<pre class="log">{log || "No build has run yet."}</pre>{/if}
              {/if}
            </details>
            {#if src.updates.length}
              <button
                class="btn start primary"
                onclick={() => repoAction("all", () => api.updatePlugins())}
                disabled={working !== null || changingPlugins || status.data?.building}
              >
                {#if working === "all"}<span class="spinner"></span> Updating…{:else}<ChevronDown
                    size={18}
                    strokeWidth={1.8}
                    aria-hidden="true"
                  /> Update {src.updates.length} plugin{src.updates.length > 1 ? "s" : ""}{/if}
              </button>
            {/if}
            {#if src.error}<div class="p-error flush" role="alert">{src.error}</div>{/if}
          </div>
        {/if}

        {#if plugins.isPending}
          <div class="plugin-skeleton" role="status">
            <span class="sr-only">Loading plugins…</span>
            {#each [0, 1, 2] as row (row)}<div><span></span><span></span></div>{/each}
          </div>
        {:else if plugins.isError}
          <div class="plugin-empty" role="alert">
            <p>Could not load plugins. Check your connection and try again.</p>
            <button class="btn" onclick={() => plugins.refetch()}>Try again</button>
          </div>
        {:else if !plugins.data?.length}
          <p class="muted small">No plugins found. Check the source and its settings above.</p>
        {:else}
          <div class="plugin-tools">
            <label class="plugin-search">
              <Search size={18} aria-hidden="true" />
              <input
                type="search"
                bind:value={pluginSearch}
                placeholder="Search plugins or providers"
                aria-label="Search plugins or providers"
              />
            </label>
            <select bind:value={pluginFilter} aria-label="Filter plugins">
              <option value="all">All plugins</option>
              <option value="enabled">Enabled</option>
              <option value="disabled">Disabled</option>
              <option value="attention">Needs attention</option>
            </select>
          </div>
          <div class="plugin-list-heading">
            <p class="plugin-count" aria-live="polite">
              {pluginSearch.trim() || pluginFilter !== "all"
                ? `${visiblePlugins.length} of ${plugins.data.length}`
                : plugins.data.length} plugins
            </p>
            <span>{plugins.data.filter((p) => p.status === "loaded").length} loaded</span>
          </div>
          {#if !visiblePlugins.length}
            <div class="plugin-empty">
              <p>No plugins match your search and filter.</p>
              <button
                class="link-btn"
                onclick={() => {
                  pluginSearch = "";
                  pluginFilter = "all";
                }}>Clear search and filter</button
              >
            </div>
          {/if}
          <ul class="plugins">
            {#each visiblePlugins as p (p.name)}
              {@const changing = pluginChanges[p.name] !== undefined}
              {@const updating = working === p.name || (working === "all" && hasUpdate(p))}
              {@const busy = changing || updating}
              {@const warning = p.status === "loaded" && !!linkWarning(p)}
              <li class={{ "plugin-busy": busy }}>
                <div class="p-row">
                  <button
                    class="p-name"
                    aria-expanded={openPlugin === p.name}
                    aria-controls="plugin-details-{p.name}"
                    onclick={() => (openPlugin = openPlugin === p.name ? null : p.name)}
                  >
                    <span class="plugin-icon" aria-hidden="true">
                      {#if p.iconUrl && failedIcons[p.name] !== p.iconUrl}
                        <img
                          src={p.iconUrl}
                          alt=""
                          width="40"
                          height="40"
                          loading="lazy"
                          referrerpolicy="no-referrer"
                          onerror={() => {
                            failedIcons[p.name] = p.iconUrl!;
                          }}
                        />
                      {:else}<Puzzle size={23} strokeWidth={1.6} />{/if}
                    </span>
                    <span class="plugin-copy">
                      <span class="plugin-title"
                        ><b>{p.name}</b><ChevronDown size={15} aria-hidden="true" /></span
                      >
                      <span class="plugin-meta"
                        >{p.version != null ? `v${p.version}` : "Version unavailable"}{hasUpdate(p)
                          ? ` → v${p.available}`
                          : ""}{p.providers.length ? ` · ${p.providers.join(", ")}` : ""}{p.nsfw
                          ? " · NSFW"
                          : ""}</span
                      >
                    </span>
                  </button>
                  <div class="plugin-actions">
                    {#if hasUpdate(p) || (p.status === "missing" && p.available != null && p.enabled)}
                      <button
                        class="btn small-btn"
                        onclick={() => repoAction(p.name, () => api.updatePlugins([p.name]))}
                        disabled={working !== null || changingPlugins || status.data?.building}
                      >
                        {#if updating}<span class="spinner" aria-hidden="true"
                          ></span>{/if}{p.status === "missing" ? "Install" : "Update"}
                      </button>
                    {/if}
                    <label class="plugin-toggle">
                      <input
                        type="checkbox"
                        role="switch"
                        bind:checked={
                          () => pluginChanges[p.name] ?? p.enabled,
                          (checked) => {
                            void setEnabled(p.name, checked);
                          }
                        }
                        disabled={busy || working !== null || status.data?.building}
                        aria-label="Enable {p.name}"
                        aria-describedby="plugin-state-{p.name}"
                      />
                    </label>
                  </div>
                </div>
                <div class="plugin-feedback" id="plugin-state-{p.name}" aria-live="polite">
                  {#if busy}<p class="p-progress">
                      {changing
                        ? pluginChanges[p.name]
                          ? "Loading the plugin and its providers…"
                          : "Unloading the plugin and its providers…"
                        : "Downloading and loading the plugin…"}
                    </p>
                  {:else if pluginErrors[p.name] || p.error}<p class="p-error">
                      <CircleAlert size={15} aria-hidden="true" />{pluginErrors[p.name] || p.error}
                    </p>
                  {:else if p.status === "error"}<p class="p-error">
                      <CircleAlert size={15} aria-hidden="true" />Failed to load this plugin.
                    </p>
                  {:else if p.status === "missing"}<p class="p-warn">
                      <CircleAlert size={15} aria-hidden="true" />Plugin not installed.
                    </p>
                  {:else if warning}<p class="p-warn">
                      <CircleAlert size={15} aria-hidden="true" />{linkWarning(p)}
                    </p>{/if}
                </div>
                {#if openPlugin === p.name}
                  <div class="p-more" id="plugin-details-{p.name}">
                    {#if p.description}<p class="muted small">
                        {p.description}
                      </p>{/if}
                    {#if p.linkage.length > 1}
                      <pre class="log">{p.linkage.join("\n")}</pre>
                    {/if}
                    {#if p.previous != null}
                      <button
                        class="link-btn"
                        onclick={() => repoAction(p.name, () => api.rollbackPlugin(p.name))}
                        disabled={working !== null || changingPlugins || status.data?.building}
                        >Roll back to v{p.previous}</button
                      >
                    {/if}
                    {#if p.status === "loaded" && !busy}<PluginSettings
                        name={p.name}
                        schema={p.settingsSchema ?? []}
                      />{/if}
                  </div>
                {/if}
              </li>
            {/each}
          </ul>
        {/if}
      </section>
    </div>
  </div>
</div>

<style>
  .settings-page {
    max-width: 1220px;
  }
  .settings-heading {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 16px;
    margin-bottom: 36px;
  }
  .settings-heading .page-title {
    font-size: 30px;
    margin: 0 0 8px;
  }
  .settings-heading p {
    margin: 0;
    color: var(--muted);
    font-size: 14px;
  }
  .settings-heading .save-status {
    display: flex;
    align-items: center;
    gap: 7px;
    font-size: 12px;
  }
  .save-status .spinner {
    width: 14px;
    height: 14px;
  }
  .settings-heading .save-status.failed {
    color: var(--danger);
  }
  .settings-layout {
    display: grid;
    grid-template-columns: 180px minmax(0, 1fr);
    gap: 36px;
    align-items: start;
  }
  .settings-nav {
    position: sticky;
    top: 32px;
    display: flex;
    flex-direction: column;
    gap: 4px;
  }
  .settings-nav a {
    display: flex;
    align-items: center;
    gap: 11px;
    min-height: 44px;
    padding: 10px 12px;
    border-radius: var(--radius-sm);
    color: var(--muted);
    font-size: 14px;
    font-weight: 500;
    white-space: nowrap;
  }
  .settings-nav a:hover {
    color: var(--text);
    background: var(--surface);
  }
  .settings-nav a.selected {
    color: var(--accent);
    background: var(--accent-soft);
  }
  .settings-content {
    min-width: 0;
  }
  section {
    display: flex;
    flex-direction: column;
    gap: 20px;
    min-width: 0;
  }
  section[hidden] {
    display: none;
  }
  h2 {
    font-size: 22px;
    font-weight: 650;
  }
  h3 {
    font-size: 13px;
    font-weight: 600;
    color: var(--muted);
    margin-top: 12px;
  }
  .section-heading {
    padding-bottom: 20px;
    border-bottom: 1px solid var(--line);
  }
  .section-heading p {
    margin: 8px 0 0;
    color: var(--muted);
    font-size: 14px;
    max-width: 65ch;
  }
  section > p {
    margin: 0;
  }
  .loading-settings {
    display: grid;
    gap: 20px;
  }
  .loading-settings div {
    height: 80px;
    border-radius: var(--radius-sm);
    background: var(--surface);
  }
  .loading-settings div:first-child {
    height: 64px;
    width: 70%;
  }
  .load-error {
    padding: 24px;
    border: 1px solid var(--line);
    border-radius: var(--radius);
  }
  .load-error p {
    color: var(--muted);
  }
  .setting-row {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(190px, 230px);
    align-items: center;
    gap: 24px;
  }
  .setting-copy {
    display: grid;
    gap: 5px;
  }
  .setting-row select {
    width: 100%;
  }
  .advanced {
    margin-top: 12px;
    border-block: 1px solid var(--line);
  }
  .advanced summary {
    display: flex;
    align-items: center;
    justify-content: space-between;
    min-height: 56px;
    cursor: pointer;
    list-style: none;
    font-size: 14px;
    font-weight: 600;
  }
  .advanced summary::-webkit-details-marker {
    display: none;
  }
  .advanced summary:hover {
    color: var(--accent);
  }
  .advanced[open] summary :global(svg) {
    transform: rotate(180deg);
  }
  .advanced-fields {
    display: grid;
    gap: 24px;
    padding: 8px 0 24px;
  }
  b {
    font-weight: 550;
    font-size: 14px;
  }
  .small {
    font-size: 13.5px;
  }
  .toggle {
    display: flex;
    min-height: 44px;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    cursor: pointer;
  }
  .toggle span,
  .stack {
    display: flex;
    flex-direction: column;
    gap: 5px;
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
    border: 1px solid var(--muted);
    transition: background 0.15s;
  }
  input[type="checkbox"]::after {
    content: "";
    position: absolute;
    top: 2px;
    left: 2px;
    width: 22px;
    height: 22px;
    border-radius: 50%;
    background: #fff;
    transition: transform 0.15s;
  }
  input[type="checkbox"]:checked {
    background: var(--accent);
    border-color: var(--accent);
  }
  input[type="checkbox"]:disabled {
    opacity: 0.5;
    cursor: wait;
  }
  input[type="checkbox"]:checked::after {
    transform: translateX(18px);
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
    align-items: flex-start;
    justify-content: space-between;
    gap: 20px;
    padding-bottom: 20px;
    border-bottom: 1px solid var(--line);
  }
  .row-head .section-heading {
    border: 0;
    padding: 0;
  }
  .link-btn {
    color: var(--accent);
    font-weight: 600;
    min-height: 44px;
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
  .plugin-source {
    display: grid;
    gap: 12px;
    padding-bottom: 20px;
    border-bottom: 1px solid var(--line);
  }
  .source-details summary {
    display: flex;
    align-items: center;
    gap: 8px;
    width: fit-content;
    min-height: 36px;
    color: var(--muted);
    font-size: 13px;
    cursor: pointer;
    list-style: none;
  }
  .source-details summary::-webkit-details-marker {
    display: none;
  }
  .source-details summary:hover {
    color: var(--text);
  }
  .source-details[open] summary :global(svg) {
    transform: rotate(180deg);
  }
  .source-details[open] {
    display: grid;
    gap: 12px;
  }
  .source-details p {
    margin: 8px 0 0;
    line-height: 1.65;
  }
  .plugins {
    list-style: none;
    margin: 0;
    padding: 0;
  }
  .plugin-tools {
    display: flex;
    flex-wrap: wrap;
    gap: 12px;
    align-items: center;
  }
  .plugin-search {
    position: relative;
    flex: 1 1 220px;
    min-width: 0;
  }
  .plugin-search > :global(svg) {
    position: absolute;
    left: 14px;
    top: 14px;
    color: var(--muted);
    pointer-events: none;
  }
  .plugin-search input {
    width: 100%;
    padding-left: 42px;
  }
  .plugin-tools select {
    flex: 0 1 180px;
  }
  .plugin-list-heading {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    color: var(--muted);
    font-size: 12.5px;
    margin-bottom: -16px;
  }
  .plugin-count {
    margin: 0;
  }
  .plugin-empty {
    padding: 20px 0;
  }
  .plugin-empty p {
    margin: 0 0 10px;
  }
  .plugins > li {
    border-bottom: 1px solid var(--line);
    padding: 12px 0;
  }
  .p-row {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 8px 16px;
  }
  .p-name {
    display: flex;
    align-items: center;
    gap: 14px;
    flex: 1 1 180px;
    min-width: 0;
    text-align: left;
    padding: 4px 0;
  }
  .plugin-icon {
    display: grid;
    place-items: center;
    flex-shrink: 0;
    width: 48px;
    height: 48px;
    border-radius: var(--radius-sm);
    background: var(--surface-2);
    color: var(--muted);
    overflow: hidden;
  }
  .plugin-icon img {
    width: 40px;
    height: 40px;
    object-fit: contain;
  }
  .plugin-copy {
    display: flex;
    flex-direction: column;
    gap: 5px;
    min-width: 0;
  }
  .plugin-title {
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .plugin-title b {
    overflow-wrap: anywhere;
    font-weight: 600;
  }
  .plugin-title :global(svg) {
    flex-shrink: 0;
    color: var(--muted);
    transition: transform 150ms ease;
  }
  .p-name[aria-expanded="true"] .plugin-title :global(svg) {
    transform: rotate(180deg);
  }
  .p-name:hover .plugin-title {
    color: var(--accent);
  }
  .plugin-meta {
    color: var(--muted);
    font-size: 12.5px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .p-progress {
    color: var(--accent);
  }
  .plugin-actions {
    display: flex;
    align-items: center;
    gap: 16px;
    flex-shrink: 0;
    margin-left: auto;
  }
  .plugin-toggle {
    display: flex;
    align-items: center;
    justify-content: flex-end;
    gap: 10px;
    min-height: 44px;
    font-size: 12px;
    color: var(--muted);
    cursor: pointer;
  }
  .plugin-busy .plugin-toggle {
    color: var(--accent);
    cursor: wait;
  }
  .plugin-feedback,
  .p-more {
    margin-left: 62px;
  }
  .p-progress,
  .p-error,
  .p-warn {
    display: flex;
    align-items: flex-start;
    gap: 7px;
    margin: 6px 0 0;
    font-size: 12.5px;
    line-height: 1.6;
    overflow-wrap: anywhere;
  }
  .p-error {
    color: var(--danger);
  }
  .p-warn {
    color: #f2c14e;
  }
  .p-error :global(svg),
  .p-warn :global(svg) {
    flex-shrink: 0;
    margin-top: 2px;
  }
  .p-error.flush {
    margin: 0;
  }
  .p-more {
    display: grid;
    gap: 12px;
    margin-top: 12px;
    padding: 0 0 4px;
  }
  .plugin-skeleton {
    display: grid;
    gap: 1px;
  }
  .plugin-skeleton > div {
    display: flex;
    align-items: center;
    gap: 14px;
    padding: 20px 0;
    border-bottom: 1px solid var(--line);
  }
  .plugin-skeleton span:not(.sr-only) {
    width: 48px;
    height: 48px;
    border-radius: var(--radius-sm);
    background: var(--surface-2);
  }
  .plugin-skeleton span + span {
    width: 45%;
    height: 32px;
  }
  @media (max-width: 480px) {
    .plugin-tools select {
      flex: 1 1 100%;
    }
    .plugin-meta {
      white-space: normal;
      overflow-wrap: anywhere;
    }
    .plugin-actions {
      gap: 10px;
    }
  }
  @media (prefers-reduced-motion: reduce) {
    .plugin-toggle input,
    .plugin-toggle input::after,
    .plugin-title :global(svg) {
      transition: none;
    }
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
    padding: 2px 0;
    gap: 16px;
    border-top: 1px solid var(--line);
    font-size: 14px;
  }
  .hosts li span {
    overflow-wrap: anywhere;
    min-width: 0;
  }
  @media (max-width: 1199px) {
    .settings-layout {
      grid-template-columns: 155px minmax(0, 1fr);
      gap: 24px;
    }
    .setting-row {
      grid-template-columns: minmax(0, 1fr);
      gap: 12px;
    }
  }
  @media (max-width: 699px) {
    .settings-heading {
      flex-direction: column;
      align-items: flex-start;
      gap: 12px;
      margin-bottom: 24px;
    }
    .settings-layout {
      display: block;
    }
    .settings-nav {
      position: static;
      flex-direction: row;
      overflow-x: auto;
      margin: 0 -20px 28px;
      padding: 0 20px 12px;
      gap: 6px;
    }
    .settings-nav a {
      flex-shrink: 0;
    }
    .settings-nav a:not(.selected) {
      background: var(--surface);
    }
    .settings-content {
      padding-bottom: 24px;
    }
    .row-head {
      flex-wrap: wrap;
      gap: 16px;
    }
    .section-heading {
      padding-bottom: 20px;
    }
    h2 {
      font-size: 21px;
    }
    .pair {
      align-items: flex-start;
    }
    .pair-text {
      flex-basis: 100%;
    }
    .plugin-tools {
      margin-top: 4px;
    }
  }
  @media (max-width: 359px) {
    .settings-nav {
      margin-inline: -16px;
      padding-inline: 16px;
    }
    .p-row {
      gap: 8px;
    }
    .small-btn {
      padding-inline: 8px;
    }
  }
</style>
