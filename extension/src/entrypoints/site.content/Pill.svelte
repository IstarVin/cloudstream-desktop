<script lang="ts">
  import { ChevronDown, CircleAlert, EyeOff, LoaderCircle, X } from "@lucide/svelte";
  import DeviceList from "../../components/DeviceList.svelte";
  import Logo from "../../components/Logo.svelte";
  import { send } from "../../lib/messages";
  import type { DeviceInfo } from "../../lib/types";

  let { provider, onclose }: { provider: { name: string }; onclose: () => void } = $props();

  let menuOpen = $state(false);
  let devices = $state<DeviceInfo[] | null>(null);
  let loading = $state(false);
  let busy = $state<string | null>(null);
  let opening = $state(false);
  let status = $state<{ ok: boolean; text: string } | null>(null);
  let statusTimer: ReturnType<typeof setTimeout> | undefined;

  function show(next: { ok: boolean; text: string }) {
    status = next;
    clearTimeout(statusTimer);
    statusTimer = setTimeout(() => (status = null), next.ok ? 4000 : 9000);
  }

  // location.href at click time, so single-page sites that change the URL without reloading still work.
  async function open() {
    opening = true;
    const r = await send("open", { url: location.href }).catch((e) => ({
      ok: false as const,
      error: String(e),
    }));
    opening = false;
    if (!r.ok) show({ ok: false, text: r.error });
  }

  async function toggleMenu() {
    menuOpen = !menuOpen;
    if (!menuOpen) return;
    loading = true;
    const r = await send("devices", {}).catch(() => null);
    loading = false;
    if (r?.ok) devices = r.devices;
    else if (r) show({ ok: false, text: r.error });
  }

  async function play(d: DeviceInfo) {
    busy = d.id;
    const r = await send("play", { url: location.href, device: d.id }).catch((e) => ({
      ok: false as const,
      error: String(e),
    }));
    busy = null;
    menuOpen = false;
    show(r.ok ? { ok: true, text: r.message } : { ok: false, text: r.error });
  }

  async function hideHere() {
    try {
      await send("hide", { url: location.href, hidden: true });
      onclose();
    } catch (e) {
      menuOpen = false;
      show({ ok: false, text: String(e) });
    }
  }

  function onkeydown(e: KeyboardEvent) {
    if (e.key === "Escape" && menuOpen) menuOpen = false;
  }
</script>

<svelte:window {onkeydown} />

<div class="wrap">
  {#if status}
    <p class={["status", status.ok ? "ok" : "bad"]} role="status">
      {#if !status.ok}<CircleAlert size={14} aria-hidden="true" />{/if}
      <span>{status.text}</span>
    </p>
  {/if}

  {#if menuOpen}
    <div class="menu" role="group" aria-label="Play on">
      <p class="label">Play on</p>
      <DeviceList {devices} {loading} {busy} onpick={play} />
      <div class="sep"></div>
      <button class="item" onclick={hideHere}>
        <EyeOff size={16} strokeWidth={1.8} aria-hidden="true" /> Hide on this site
      </button>
    </div>
  {/if}

  <div class="pill">
    <button
      class="main"
      onclick={open}
      disabled={opening}
      title="Open this page in Cloudstream ({provider.name})"
    >
      {#if opening}
        <LoaderCircle class="spin" size={18} aria-hidden="true" />
      {:else}
        <span class="mark"><Logo size={18} /></span>
      {/if}
      <span>Open in Cloudstream</span>
    </button>
    <button
      class="more"
      onclick={toggleMenu}
      aria-expanded={menuOpen}
      title="Play on a device"
      aria-label="Play on a device"
    >
      <ChevronDown size={16} strokeWidth={2} aria-hidden="true" class={menuOpen ? "flip" : ""} />
    </button>
    <button class="close" onclick={onclose} title="Close" aria-label="Close">
      <X size={14} strokeWidth={2} aria-hidden="true" />
    </button>
  </div>
</div>

<style>
  .wrap {
    position: fixed;
    right: 16px;
    bottom: 16px;
    z-index: 2147483646;
    display: flex;
    flex-direction: column;
    align-items: flex-end;
    gap: 8px;
    max-width: calc(100vw - 32px);
    color: var(--text);
    font: 14px/1.4 var(--font);
    -webkit-font-smoothing: antialiased;
  }
  .wrap :global(*) {
    box-sizing: border-box;
  }
  button {
    font: inherit;
    color: inherit;
    cursor: pointer;
  }
  button:focus-visible {
    outline: 2px solid var(--accent);
    outline-offset: 2px;
  }
  .pill {
    display: flex;
    align-items: stretch;
    height: 40px;
    border: 1px solid var(--divider);
    border-radius: 999px;
    background: var(--surface);
    box-shadow: 0 8px 28px rgb(0 0 0 / 45%);
    overflow: hidden;
  }
  .main,
  .more,
  .close {
    display: flex;
    align-items: center;
    border: 0;
    background: transparent;
  }
  .main {
    gap: 8px;
    padding: 0 12px 0 14px;
    font-weight: 600;
    white-space: nowrap;
  }
  .mark {
    display: grid;
    color: var(--accent);
  }
  .more {
    padding: 0 10px;
    border-left: 1px solid var(--divider);
  }
  .close {
    padding: 0 12px 0 8px;
    color: var(--muted);
  }
  .main:hover:not(:disabled),
  .more:hover,
  .more[aria-expanded="true"] {
    background: var(--surface-3);
  }
  .close:hover {
    color: var(--text);
  }
  .main:disabled {
    cursor: progress;
  }
  :global(.flip) {
    transform: rotate(180deg);
  }
  .menu {
    width: 260px;
    padding: 6px;
    border: 1px solid var(--divider);
    border-radius: var(--radius-lg);
    background: var(--surface-2);
    box-shadow: 0 8px 28px rgb(0 0 0 / 45%);
  }
  .label {
    margin: 4px 10px 6px;
    color: var(--muted);
    font-size: 11px;
    font-weight: 600;
    letter-spacing: 0.06em;
    text-transform: uppercase;
  }
  .sep {
    height: 1px;
    margin: 6px 4px;
    background: var(--divider);
  }
  .item {
    display: flex;
    align-items: center;
    gap: 10px;
    width: 100%;
    padding: 8px 10px;
    border: 0;
    border-radius: var(--radius);
    background: transparent;
    color: var(--muted);
    text-align: left;
  }
  .item:hover {
    background: var(--surface-3);
    color: var(--text);
  }
  .status {
    display: flex;
    align-items: flex-start;
    gap: 6px;
    max-width: 320px;
    margin: 0;
    padding: 8px 12px;
    border: 1px solid var(--divider);
    border-radius: var(--radius);
    background: var(--surface-2);
    box-shadow: 0 8px 28px rgb(0 0 0 / 45%);
    font-size: 13px;
  }
  .status :global(svg) {
    flex: none;
    margin-top: 2px;
  }
  .ok {
    color: var(--success);
  }
  .bad {
    color: var(--error);
  }
</style>
