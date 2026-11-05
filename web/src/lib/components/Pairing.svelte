<script lang="ts">
  import { api, ApiError, type DeviceAuthorization } from "#lib/api";
  import { onMount, onDestroy } from "svelte";
  import QRCode from "qrcode";

  let info = $state<Awaited<ReturnType<typeof api.authStatus>> | null>(null);
  let grant = $state<DeviceAuthorization | null>(null);
  let qr = $state("");
  let username = $state("");
  let password = $state("");
  let method = $state<"code" | "password">("code");
  let busy = $state(false);
  let error = $state("");
  let remaining = $state(0);
  let deadline = 0;
  let generation = 0;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let ticker: ReturnType<typeof setInterval> | undefined;
  let disposed = false;
  const canPassword =
    typeof location !== "undefined" &&
    (location.protocol === "https:" ||
      ["localhost", "127.0.0.1", "[::1]"].includes(location.hostname));
  const deviceName = "Web browser";

  function stop() {
    generation++;
    clearTimeout(timer);
    clearInterval(ticker);
    grant = null;
    qr = "";
  }
  async function load() {
    error = "";
    try {
      info = await api.authStatus();
      if (info.authed) location.reload();
    } catch (e) {
      error = e instanceof Error ? e.message : "Could not reach the server. Try again.";
    }
  }
  async function start() {
    stop();
    error = "";
    busy = true;
    const current = generation;
    try {
      const next = await api.startSignIn(deviceName);
      if (disposed || current !== generation) return;
      grant = next;
      deadline = Date.now() + next.expiresIn * 1000;
      remaining = next.expiresIn;
      qr = await QRCode.toDataURL(next.verificationUri, { width: 176, margin: 2 });
      ticker = setInterval(() => {
        remaining = Math.max(0, Math.ceil((deadline - Date.now()) / 1000));
        if (!remaining) {
          stop();
          error = "This code expired. Request a new code to continue.";
        }
      }, 1000);
      timer = setTimeout(() => poll(next, current), next.interval * 1000);
    } catch (e) {
      if (current === generation)
        error = e instanceof Error ? e.message : "Could not start sign-in. Try again.";
    } finally {
      busy = false;
    }
  }
  async function poll(next: DeviceAuthorization, current: number) {
    if (disposed || current !== generation || Date.now() >= deadline) return;
    try {
      const result = await api.pollSignIn(next.deviceCode);
      if (disposed || current !== generation) return;
      if (result.status === "approved") {
        location.reload();
        return;
      }
      error = "";
    } catch (e) {
      if (disposed || current !== generation) return;
      if (e instanceof ApiError && e.status === 410) {
        stop();
        error = e.message;
        return;
      }
      error = e instanceof Error ? e.message : "Connection interrupted. Retrying…";
    }
    timer = setTimeout(() => poll(next, current), next.interval * 1000);
  }
  async function login(event: SubmitEvent) {
    event.preventDefault();
    error = "";
    busy = true;
    const secret = password;
    password = "";
    try {
      await api.login(username, secret, deviceName);
      location.reload();
    } catch (e) {
      error = e instanceof Error ? e.message : "Sign-in failed. Try again.";
    } finally {
      busy = false;
    }
  }
  function switchMethod() {
    stop();
    error = "";
    method = method === "code" ? "password" : "code";
  }
  onMount(load);
  onDestroy(() => {
    disposed = true;
    stop();
  });
</script>

<main class="sign-in">
  <div class="content">
    <img class="brand" src="/favicon.svg" alt="Cloudstream" width="48" height="48" />
    <h1>Sign in to Cloudstream</h1>
    <p class="intro">{info?.serverName ?? "Your sources and library, on this device."}</p>
    {#if info && !info.configured}
      <p role="status">
        Authentication needs to be set up on this server. Configure the Worker address and service
        secret, then restart the server.
      </p>
      <button class="btn" onclick={load}>Check again</button>
    {:else if info}
      {#if method === "code"}
        {#if grant}
          <p>Open this address on another device, sign in, and enter the code below.</p>
          <a
            class="activation"
            href={grant.verificationUri}
            target="_blank"
            rel="noopener noreferrer">{grant.verificationUri}</a
          >
          <div class="device-code" aria-label={`Device code ${grant.userCode.split("").join(" ")}`}>
            {grant.userCode.slice(0, 3)}
            {grant.userCode.slice(3)}
          </div>
          <div class="waiting">
            <span role="status">Waiting for approval</span>
            <span aria-live="off">
              · {Math.floor(remaining / 60)}:{String(remaining % 60).padStart(2, "0")}</span
            >
          </div>
          {#if qr}<img
              class="qr"
              src={qr}
              alt="QR code to open the sign-in page"
              width="176"
              height="176"
            />{/if}
          <button class="btn" onclick={start} disabled={busy}>Get a new code</button>
        {:else}
          <p>Sign in on another device to connect this browser.</p>
          <button class="btn primary" onclick={start} disabled={busy}
            >{busy ? "Getting a code…" : "Get a sign-in code"}</button
          >
        {/if}
      {:else}
        <form onsubmit={login}>
          <label
            >Username<input
              bind:value={username}
              autocomplete="username"
              required
              maxlength="64"
              disabled={busy}
            /></label
          >
          <label
            >Password<input
              bind:value={password}
              type="password"
              autocomplete="current-password"
              required
              maxlength="256"
              disabled={busy}
            /></label
          >
          <button class="btn primary" disabled={busy}>{busy ? "Signing in…" : "Sign in"}</button>
        </form>
      {/if}
      {#if canPassword}<button class="method" onclick={switchMethod} disabled={busy}
          >{method === "code" ? "Use username and password" : "Sign in on another device"}</button
        >{/if}
    {:else if !error}
      <p role="status">Connecting to the server…</p>
    {/if}
    {#if error}<p class="error" role="alert">{error}</p>
      {#if !info}<button class="btn" onclick={load}>Try again</button>{/if}{/if}
  </div>
</main>

<style>
  .sign-in {
    min-height: 100dvh;
    display: grid;
    place-items: center;
    padding: 32px 24px;
  }
  .content {
    width: 100%;
    max-width: 420px;
  }
  .brand {
    margin-bottom: 24px;
  }
  h1 {
    font-size: clamp(26px, 5vw, 32px);
    line-height: 1.2;
    letter-spacing: -0.025em;
    margin: 0 0 12px;
  }
  p {
    color: var(--muted);
    line-height: 1.6;
    margin: 0 0 20px;
  }
  .intro {
    margin-bottom: 32px;
    overflow-wrap: anywhere;
  }
  .activation {
    color: var(--accent);
    overflow-wrap: anywhere;
    text-decoration: underline;
    text-underline-offset: 4px;
  }
  .device-code {
    font-size: clamp(36px, 9vw, 52px);
    font-weight: 700;
    letter-spacing: 0.1em;
    font-variant-numeric: tabular-nums;
    margin-top: 20px;
  }
  .waiting {
    color: var(--muted);
    font-size: 14px;
    margin: 8px 0 24px;
  }
  .qr {
    display: block;
    margin-bottom: 24px;
    border-radius: 8px;
  }
  form,
  label {
    display: grid;
    gap: 8px;
  }
  form {
    gap: 20px;
  }
  label {
    font-size: 14px;
    font-weight: 600;
  }
  input {
    width: 100%;
    min-width: 0;
    min-height: 48px;
    padding: 12px;
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
    background: var(--surface);
    color: var(--text);
    font: inherit;
  }
  .btn {
    min-height: 48px;
    width: 100%;
    justify-content: center;
  }
  .method {
    display: block;
    width: 100%;
    margin-top: 16px;
    min-height: 48px;
    color: var(--accent);
    text-decoration: underline;
    text-underline-offset: 4px;
  }
  .error {
    color: var(--danger);
    margin-top: 24px;
  }
  :focus-visible {
    outline: 2px solid var(--accent);
    outline-offset: 3px;
  }
</style>
