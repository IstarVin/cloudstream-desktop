<script lang="ts">
  import { createQuery, useQueryClient } from "@tanstack/svelte-query";
  import QRCode from "qrcode";
  import { api } from "#lib/api";
  import Icon from "#lib/components/Icon.svelte";
  import PluginSettings from "#lib/components/PluginSettings.svelte";
  import { toasts } from "#lib/toast.svelte";
  import type { Settings } from "#lib/types";

  const client = useQueryClient();
  const settings = createQuery(() => ({ queryKey: ["settings"], queryFn: api.settings }));
  const status = createQuery(() => ({ queryKey: ["status"], queryFn: api.status, refetchInterval: 3000 }));
  const plugins = createQuery(() => ({
    queryKey: ["plugins"],
    queryFn: api.plugins,
    refetchInterval: () => (status.data?.building ? 2000 : false),
  }));

  let openPlugin = $state<string | null>(null);
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

  async function setEnabled(name: string, enabled: boolean) {
    await api.setPluginEnabled(name, enabled).catch(toasts.error);
    setTimeout(() => client.invalidateQueries(), 400);
  }

  async function rebuild() {
    await api.rebuild().catch(toasts.error);
    toasts.show("Rebuilding plugins…");
    client.invalidateQueries({ queryKey: ["status"] });
  }

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

  const statusColor: Record<string, string> = { loaded: "good", error: "bad", missing: "warn", disabled: "off" };
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
        <button class="btn start" onclick={showPairing}><Icon name="phone" size={18} /> Show pairing code</button>
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
        <span><b>Show NSFW providers</b><small>Off hides them everywhere: home, search, history and the plugin list.</small></span>
        <input type="checkbox" checked={s.show_nsfw} onchange={(e) => save({ show_nsfw: e.currentTarget.checked })} />
      </label>
    </section>
  {/if}

  <section>
    <div class="row-head">
      <h2>Plugins</h2>
      <button class="btn" onclick={rebuild} disabled={status.data?.building}>
        {#if status.data?.building}<span class="spinner"></span> Building…{:else}<Icon name="refresh" size={18} /> Rebuild{/if}
      </button>
    </div>
    <p class="muted small">
      Built from the local extensions repo. Any new <code>&lt;Name&gt;/build/&lt;Name&gt;.jar</code> there is reloaded
      automatically, so Rebuild (or <code>./gradlew :Name:make</code> for cross-platform plugins) is enough.
      {#if status.data}{status.data.loaded} of {status.data.plugins} loaded.{/if}
      <button class="link-btn" onclick={async () => (log = log === null ? await api.buildLog() : null)}>{log === null ? "Show build log" : "Hide build log"}</button>
    </p>
    {#if log !== null}<pre class="log">{log || "No build has run yet."}</pre>{/if}

    <ul class="plugins">
      {#each plugins.data ?? [] as p (p.name)}
        <li>
          <div class="p-row">
            <span class="dot {statusColor[p.status]}" title={p.status}></span>
            <button class="p-name" onclick={() => (openPlugin = openPlugin === p.name ? null : p.name)} aria-expanded={openPlugin === p.name}>
              <b>{p.name}</b>
              <small class="muted">v{p.version ?? "?"} · {p.providers.join(", ") || p.status}{p.nsfw ? " · NSFW" : ""}</small>
            </button>
            <input type="checkbox" checked={p.enabled} onchange={(e) => setEnabled(p.name, e.currentTarget.checked)} aria-label="Enable {p.name}" />
          </div>
          {#if p.error}<div class="p-error">{p.error}</div>{/if}
          {#if openPlugin === p.name}
            <div class="p-more">
              {#if p.description}<p class="muted small">{p.description}</p>{/if}
              {#if p.status === "loaded"}<PluginSettings name={p.name} />{/if}
            </div>
          {/if}
        </li>
      {/each}
    </ul>
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
</style>
