<script lang="ts">
  import {
    CircleAlert,
    ExternalLink,
    EyeOff,
    LoaderCircle,
    LogOut,
    Play,
    RefreshCw,
    Server,
    ShieldCheck,
  } from "@lucide/svelte";
  import { onMount } from "svelte";
  import { browser } from "wxt/browser";
  import DeviceList from "../../components/DeviceList.svelte";
  import Logo from "../../components/Logo.svelte";
  import { send, type MatchResult, type State } from "../../lib/messages";
  import * as store from "../../lib/storage";
  import type { DeviceInfo } from "../../lib/types";

  let st = $state<State | null>(null);
  let error = $state<string | null>(null);
  let busy = $state<string | null>(null);
  let editingServer = $state(false);
  let serverInput = $state("");

  let tabUrl = $state<string | null>(null);
  let match = $state<MatchResult | null>(null);
  let devices = $state<DeviceInfo[] | null>(null);
  let devicesLoading = $state(false);
  let playing = $state<string | null>(null);
  let played = $state<{ ok: boolean; text: string } | null>(null);
  let now = $state(Date.now());

  const step = $derived(
    !st
      ? "loading"
      : !st.serverUrl || editingServer
        ? "server"
        : !st.hostAccess
          ? "access"
          : !st.signedIn
            ? "signin"
            : "ready",
  );
  const secondsLeft = $derived(
    st?.pending ? Math.max(0, Math.round((st.pending.expiresAt - now) / 1000)) : 0,
  );

  async function load() {
    st = await send("state", {});
    if (st.signedIn) {
      if (tabUrl) match = await send("match", { url: tabUrl });
      if (match?.provider) loadDevices();
    }
  }

  /** The first load, retried once: right after the browser starts, the background may not be listening yet. */
  async function firstLoad() {
    try {
      await load();
    } catch {
      await new Promise((r) => setTimeout(r, 500));
      await load().catch((e) => {
        st = null;
        error = `Couldn't reach the extension's background: ${e instanceof Error ? e.message : e}`;
      });
    }
  }

  async function setHidden(hidden: boolean) {
    if (!tabUrl) return;
    await run("hide", async () => {
      await send("hide", { url: tabUrl!, hidden });
      match = await send("match", { url: tabUrl! });
    });
  }

  async function loadDevices() {
    devicesLoading = true;
    const r = await send("devices", {}).catch((e) => ({ ok: false as const, error: String(e) }));
    devicesLoading = false;
    if (r.ok) devices = r.devices;
    else error = r.error;
  }

  /** Runs an action with a busy marker; its error, if any, shows under the step. */
  async function run(name: string, action: () => Promise<{ ok: boolean; error?: string } | void>) {
    busy = name;
    error = null;
    try {
      const r = await action();
      if (r && !r.ok) error = r.error ?? "Something went wrong";
    } catch (e) {
      error = e instanceof Error ? e.message : String(e);
    } finally {
      busy = null;
    }
  }

  const connect = (e: SubmitEvent) => {
    e.preventDefault();
    return run("connect", async () => {
      const r = await send("connect", { url: serverInput });
      await load();
      if (r.ok) editingServer = false;
      return r;
    });
  };

  function grantAccess() {
    // Must be called straight from the click: browsers only show permission prompts for user gestures.
    browser.permissions
      .request({ origins: ["<all_urls>"] })
      .then(load)
      .catch((e) => (error = String(e)));
  }

  const signIn = () =>
    run("signin", async () => {
      const r = await send("signIn", {});
      await load();
      return r;
    });

  async function openApproval() {
    if (st?.pending) await browser.tabs.create({ url: st.pending.verificationUri });
  }

  async function copyCode() {
    if (st?.pending) await navigator.clipboard.writeText(st.pending.userCode).catch(() => {});
  }

  const cancelSignIn = () => run("cancel", async () => void (st = await send("cancelSignIn", {})));
  const signOut = () => run("signout", async () => void (st = await send("signOut", {})));
  const disconnect = () =>
    run("disconnect", async () => {
      st = await send("disconnect", {});
      editingServer = false;
      serverInput = "";
    });
  const refresh = () =>
    run("refresh", async () => {
      const r = await send("refresh", {});
      await load();
      return r;
    });

  async function openTitle() {
    if (!tabUrl) return;
    await run("open", async () => {
      const r = await send("open", { url: tabUrl! });
      if (r.ok) window.close();
      return r;
    });
  }

  async function playOn(d: DeviceInfo) {
    if (!tabUrl) return;
    playing = d.id;
    played = null;
    const r = await send("play", { url: tabUrl, device: d.id });
    playing = null;
    played = r.ok ? { ok: true, text: r.message } : { ok: false, text: r.error };
  }

  function editServer() {
    serverInput = st?.serverUrl ?? "";
    editingServer = true;
  }

  function ago(at: number | null) {
    if (!at) return "never";
    const min = Math.round((now - at) / 60000);
    return min < 1 ? "just now" : min < 60 ? `${min} min ago` : `${Math.round(min / 60)} h ago`;
  }

  onMount(() => {
    browser.tabs.query({ active: true, currentWindow: true }).then(([tab]) => {
      const url = tab?.url;
      tabUrl = url && /^https?:/.test(url) ? url : null;
      firstLoad();
    });
    // The background finishes the sign-in (the popup may have closed meanwhile); follow it here.
    const unwatch = [
      store.token.watch(load),
      store.pendingSignIn.watch(load),
      store.providers.watch(load),
    ];
    const timer = setInterval(() => (now = Date.now()), 1000);
    return () => {
      unwatch.forEach((u) => u());
      clearInterval(timer);
    };
  });
</script>

<header>
  <span class="mark"><Logo size={22} /></span>
  <div class="who">
    <strong>Cloudstream</strong>
    {#if st?.serverUrl && step !== "server"}
      <a href={st.serverUrl} target="_blank" rel="noreferrer" title={st.serverUrl}>
        {st.serverName ?? new URL(st.serverUrl).host}
      </a>
    {/if}
  </div>
  {#if step === "ready"}
    <button
      class="btn quiet icon"
      title="Open Cloudstream"
      onclick={() => browser.tabs.create({ url: st!.serverUrl! })}
    >
      <ExternalLink size={16} strokeWidth={1.8} aria-label="Open Cloudstream" />
    </button>
  {/if}
</header>

<main>
  {#if step === "loading"}
    {#if !error}
      <p class="center muted"><LoaderCircle class="spin" size={18} aria-hidden="true" /></p>
    {/if}
  {:else if step === "server"}
    <form onsubmit={connect}>
      <h1>Connect to your server</h1>
      <p class="muted">The address you open Cloudstream Desktop at.</p>
      <!-- svelte-ignore a11y_autofocus -->
      <input
        class="field"
        type="text"
        inputmode="url"
        autocomplete="url"
        spellcheck="false"
        placeholder="https://cloudstream.example.com"
        aria-label="Server address"
        bind:value={serverInput}
        autofocus
      />
      <div class="row">
        {#if editingServer && st?.serverUrl}
          <button type="button" class="btn quiet" onclick={() => (editingServer = false)}
            >Cancel</button
          >
        {/if}
        <button class="btn primary grow" disabled={busy !== null || !serverInput.trim()}>
          {#if busy === "connect"}<LoaderCircle
              class="spin"
              size={16}
              aria-hidden="true"
            />{:else}<Server size={16} strokeWidth={1.8} aria-hidden="true" />{/if}
          Connect
        </button>
      </div>
    </form>
  {:else if step === "access"}
    <section>
      <h1>Allow access to sites</h1>
      <p class="muted">
        To add Cloudstream actions to provider sites, the extension needs to read the pages you
        visit. It only talks to your server.
      </p>
      <button class="btn primary" onclick={grantAccess}>
        <ShieldCheck size={16} strokeWidth={1.8} aria-hidden="true" /> Allow access to all sites
      </button>
    </section>
  {:else if step === "signin"}
    <section>
      {#if st?.pending && !st.pending.error}
        <h1>Approve this browser</h1>
        <p class="muted">Open the approval page, sign in, and confirm this code:</p>
        <button class="code" title="Copy code" onclick={copyCode}>{st.pending.userCode}</button>
        <button class="btn primary" onclick={openApproval}>
          <ExternalLink size={16} strokeWidth={1.8} aria-hidden="true" /> Open approval page
        </button>
        <p class="muted small center">
          <LoaderCircle class="spin" size={12} aria-hidden="true" /> Waiting for approval ·
          {Math.floor(secondsLeft / 60)}:{String(secondsLeft % 60).padStart(2, "0")}
        </p>
        <button class="btn quiet" onclick={cancelSignIn}>Cancel</button>
      {:else}
        <h1>Sign in</h1>
        <p class="muted">Approve this browser from your Cloudstream account, as you would a TV.</p>
        {#if st?.pending?.error}
          <p class="error"><CircleAlert size={14} aria-hidden="true" /> {st.pending.error}</p>
        {/if}
        <button class="btn primary" disabled={busy !== null} onclick={signIn}>
          {#if busy === "signin"}<LoaderCircle class="spin" size={16} aria-hidden="true" />{/if}
          Sign in
        </button>
      {/if}
    </section>
  {:else if step === "ready"}
    <section class="tab">
      {#if match?.provider}
        <p class="eyebrow">On this site</p>
        <div class="provider">
          {#if match.provider.iconUrl}
            <img src={match.provider.iconUrl} alt="" width="28" height="28" />
          {:else}
            <span class="ph"><Logo size={16} /></span>
          {/if}
          <strong>{match.provider.name}</strong>
        </div>
        <button class="btn primary" disabled={busy !== null} onclick={openTitle}>
          {#if busy === "open"}<LoaderCircle
              class="spin"
              size={16}
              aria-hidden="true"
            />{:else}<ExternalLink size={16} strokeWidth={1.8} aria-hidden="true" />{/if}
          Open this page in Cloudstream
        </button>
        <p class="eyebrow"><Play size={11} fill="currentColor" aria-hidden="true" /> Play on</p>
        <DeviceList {devices} loading={devicesLoading} busy={playing} onpick={playOn} />
        {#if played}
          <p class={played.ok ? "ok" : "error"}>
            {#if !played.ok}<CircleAlert size={14} aria-hidden="true" />{/if}
            {played.text}
          </p>
        {/if}
        <p class="muted small hidden-note">
          {#if match.hidden}
            <EyeOff size={12} aria-hidden="true" /> Page button hidden on this site ·
            <button class="link" disabled={busy !== null} onclick={() => setHidden(false)}
              >Show again</button
            >
          {:else}
            <button class="link" disabled={busy !== null} onclick={() => setHidden(true)}
              >Hide the page button on this site</button
            >
          {/if}
        </p>
      {:else}
        <p class="muted center">
          {tabUrl
            ? "This site isn't one of your providers."
            : "Open a provider's site to use Cloudstream on it."}
        </p>
        <p class="muted small center">
          Right-click a title link on a provider site to open or play it.
        </p>
      {/if}
    </section>
  {/if}

  {#if error}
    <p class="error"><CircleAlert size={14} aria-hidden="true" /> {error}</p>
  {/if}
</main>

{#if st?.serverUrl && step !== "server" && step !== "loading"}
  <footer>
    {#if step === "ready"}
      <span class="muted small"
        >{st.providerCount}
        {st.providerCount === 1 ? "site" : "sites"} · updated {ago(st.providersFetchedAt)}</span
      >
      <button
        class="btn quiet icon"
        title="Refresh providers"
        disabled={busy !== null}
        onclick={refresh}
      >
        <RefreshCw
          class={busy === "refresh" ? "spin" : ""}
          size={15}
          strokeWidth={1.8}
          aria-label="Refresh providers"
        />
      </button>
    {/if}
    <span class="grow"></span>
    <button class="btn quiet small" onclick={editServer}>Change server</button>
    {#if st.signedIn}
      <button class="btn quiet icon" title="Sign out" onclick={signOut}>
        <LogOut size={15} strokeWidth={1.8} aria-label="Sign out" />
      </button>
    {:else}
      <button class="btn quiet small" onclick={disconnect}>Forget</button>
    {/if}
  </footer>
{/if}

<style>
  header {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 12px 14px;
    border-bottom: 1px solid var(--divider);
    background: var(--surface);
  }
  .mark {
    display: grid;
    color: var(--accent);
  }
  .who {
    display: grid;
    flex: 1;
    min-width: 0;
    line-height: 1.25;
  }
  .who a {
    overflow: hidden;
    color: var(--muted);
    font-size: 12px;
    text-decoration: none;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .who a:hover {
    color: var(--text);
  }
  main {
    padding: 16px 14px;
  }
  form,
  section {
    display: grid;
    gap: 10px;
  }
  h1 {
    margin: 0;
    font-size: 16px;
    letter-spacing: -0.01em;
  }
  p {
    margin: 0;
  }
  .muted {
    color: var(--muted);
  }
  .small {
    font-size: 12px;
  }
  .center {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 6px;
    text-align: center;
  }
  .row {
    display: flex;
    gap: 8px;
  }
  .grow {
    flex: 1;
  }
  .icon {
    min-width: 32px;
    min-height: 32px;
    padding: 0;
  }
  .btn.small {
    min-height: 30px;
    padding: 0 8px;
    font-size: 12px;
  }
  .code {
    padding: 12px;
    border: 1px dashed var(--divider);
    border-radius: var(--radius);
    background: var(--surface-2);
    color: var(--text);
    font:
      700 24px/1 ui-monospace,
      monospace;
    letter-spacing: 0.18em;
    text-align: center;
    cursor: copy;
  }
  .eyebrow {
    display: flex;
    align-items: center;
    gap: 5px;
    margin-top: 4px;
    color: var(--muted);
    font-size: 11px;
    font-weight: 600;
    letter-spacing: 0.06em;
    text-transform: uppercase;
  }
  .provider {
    display: flex;
    align-items: center;
    gap: 10px;
  }
  .provider img,
  .ph {
    display: grid;
    place-items: center;
    width: 28px;
    height: 28px;
    border-radius: var(--radius);
    background: var(--surface-2);
    color: var(--accent);
    object-fit: cover;
  }
  .error,
  .ok {
    display: flex;
    align-items: flex-start;
    gap: 6px;
    margin-top: 10px;
    font-size: 13px;
  }
  .error {
    color: var(--error);
  }
  .error :global(svg) {
    flex: none;
    margin-top: 2px;
  }
  .ok {
    color: var(--success);
  }
  .hidden-note {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 4px;
    margin-top: 4px;
  }
  .link {
    padding: 0;
    border: 0;
    background: none;
    color: var(--accent);
    font: inherit;
    cursor: pointer;
  }
  .link:hover:not(:disabled) {
    text-decoration: underline;
  }
  .link:disabled {
    cursor: default;
    opacity: 0.6;
  }
  footer {
    display: flex;
    align-items: center;
    gap: 4px;
    padding: 6px 8px 6px 14px;
    border-top: 1px solid var(--divider);
    background: var(--surface);
  }
</style>
