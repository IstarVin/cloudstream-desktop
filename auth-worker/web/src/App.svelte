<script lang="ts">
  import { tick } from "svelte";
  import { ArrowRight, ChevronRight, LockKeyhole, Monitor, Settings, UserRound } from "@lucide/svelte";
  import { api, ApiError } from "./api";

  let username = $state<string | null>(null); // null while loading, "" when signed out
  let message = $state("");
  let isError = $state(false);
  let busy = $state(false);

  let loginUser = $state("");
  let loginPass = $state("");
  let userCode = $state("");
  let checkedCode = $state("");
  let approval = $state<{ serverName: string; deviceName: string } | null>(null);
  let accountUser = $state("");
  let currentPass = $state("");
  let newPass = $state("");

  let codeInput = $state<HTMLInputElement>();
  let approvalHeading = $state<HTMLElement>();

  function setUser(name: string) {
    username = name;
    accountUser = name;
  }
  function say(text: string, error = false) {
    message = text;
    isError = error;
  }

  async function run(fn: () => Promise<HTMLElement | undefined | void>) {
    say("");
    busy = true;
    let focus: HTMLElement | undefined | void;
    try {
      focus = await fn();
    } catch (e) {
      if (e instanceof ApiError && e.status === 401 && username) {
        setUser("");
        approval = null;
      }
      say((e as Error).message, true);
    } finally {
      busy = false;
    }
    await tick();
    focus?.focus();
  }

  api("account")
    .then((r) => setUser(r.username))
    .catch((e: Error) => {
      setUser("");
      if (e.message !== "Sign in to continue") say(e.message, true);
    });

  const login = (e: SubmitEvent) => {
    e.preventDefault();
    run(async () => {
      const data = { username: loginUser, password: loginPass };
      loginPass = "";
      setUser((await api("login", data)).username);
      await tick();
      return codeInput;
    });
  };
  const logout = () =>
    run(async () => {
      await api("logout", {});
      approval = null;
      setUser("");
    });
  const lookup = (e: SubmitEvent) => {
    e.preventDefault();
    run(async () => {
      approval = null;
      checkedCode = userCode.toUpperCase().replace(/[\s-]/g, "");
      approval = await api("device/lookup", { userCode: checkedCode });
      await tick();
      return approvalHeading;
    });
  };
  const decide = (approve: boolean) =>
    run(async () => {
      await api("device/approve", { userCode: checkedCode, approve });
      approval = null;
      userCode = checkedCode = "";
      say(approve ? "Device approved. It will sign in automatically." : "Sign-in declined.");
    });
  const saveAccount = (e: SubmitEvent) => {
    e.preventDefault();
    run(async () => {
      const data = { username: accountUser, currentPassword: currentPass, password: newPass };
      currentPass = newPass = "";
      setUser((await api("account", data)).username);
      approval = null;
      say("Credentials saved. Other devices will be signed out within one minute.");
    });
  };
  const revokeAll = () =>
    run(async () => {
      if (!confirm("Sign out every device on all your servers?")) return;
      await api("revoke-all", {});
      approval = null;
      say("Devices will be signed out within one minute.");
    });
</script>

<div class="page-shell">
  <header class="site-header">
    <a class="brand" href="/" aria-label="Cloudstream home">
      <svg class="brand-mark" viewBox="0 0 108 108" aria-hidden="true"
        ><path
          fill="currentColor"
          fill-rule="evenodd"
          d="M34 79C23 79 16 71 16 61C16 51 23 43 33 42C36 31 45 25 56 27C66 28 73 35 75 45C85 45 92 52 92 62C92 72 84 79 74 79ZM47 45C45.7 44.2 44 45.2 44 46.7V66.3C44 67.8 45.7 68.8 47 68L63.5 58.2C64.8 57.4 64.8 55.6 63.5 54.8Z"
        /></svg
      >
      <span>Cloudstream</span>
    </a>
    <span class="header-label">Account</span>
  </header>

  <main>
    {#if username === null}
      <div class="loading" role="status">
        <span class="loading-line"></span><span class="loading-line short"></span><span
          class="loading-text">Opening your account…</span
        >
      </div>
    {:else if !username}
      <section class="portal" aria-labelledby="page-title">
        <div class="section-icon" aria-hidden="true">
          <LockKeyhole size={20} strokeWidth={1.7} />
        </div>
        <h1 id="page-title">Sign in</h1>
        <p class="intro">Use your Cloudstream owner account to connect a device.</p>
        <form onsubmit={login}>
          <label
            >Username<input
              bind:value={loginUser}
              autocomplete="username"
              required
              maxlength="64"
            /></label
          >
          <label
            >Password<input
              bind:value={loginPass}
              type="password"
              autocomplete="current-password"
              required
              maxlength="256"
            /></label
          >
          <button class="primary full" disabled={busy}
            >Sign in <ArrowRight size={20} strokeWidth={1.7} aria-hidden="true" /></button
          >
        </form>
      </section>
    {:else}
      <div class="account-line">
        <div class="account-identity">
          <span class="avatar" aria-hidden="true"
            ><UserRound size={20} strokeWidth={1.7} /></span
          ><span><span class="account-label">Signed in as</span><strong>{username}</strong></span>
        </div>
        <button class="text-button" disabled={busy} onclick={logout}>Sign out</button>
      </div>
      <section class="portal" aria-labelledby="page-title">
        <div class="section-icon" aria-hidden="true">
          <Monitor size={20} strokeWidth={1.7} />
        </div>
        <h1 id="page-title">Connect a device</h1>
        <p class="intro">Enter the six-character code shown on your TV, phone, or browser.</p>
        <form onsubmit={lookup}>
          <label
            >Device code
            <input
              bind:this={codeInput}
              bind:value={userCode}
              oninput={() => {
                approval = null;
                checkedCode = "";
              }}
              class="code"
              autocomplete="off"
              autocapitalize="characters"
              spellcheck="false"
              maxlength="7"
              placeholder="ABC 234"
              required
            />
          </label>
          <button class="primary full" disabled={busy}
            >Check device <ArrowRight size={20} strokeWidth={1.7} aria-hidden="true" /></button
          >
        </form>

        {#if approval}
          <div id="approval" role="region" aria-labelledby="approval-heading">
            <h2 id="approval-heading" tabindex="-1" bind:this={approvalHeading}>
              Is this your device?
            </h2>
            <p>Check these details before approving the sign-in.</p>
            <dl>
              <div>
                <dt>Server</dt>
                <dd>{approval.serverName}</dd>
              </div>
              <div>
                <dt>Device</dt>
                <dd>{approval.deviceName}</dd>
              </div>
            </dl>
            <p class="approval-note">
              Approve only if this code is displayed on the device you are signing in to.
            </p>
            <div class="actions">
              <button class="primary" disabled={busy} onclick={() => decide(true)}
                >Approve sign-in</button
              ><button class="secondary" disabled={busy} onclick={() => decide(false)}
                >Decline</button
              >
            </div>
          </div>
        {/if}
      </section>

      <details class="settings">
        <summary
          ><span class="settings-title"
            ><span class="settings-icon" aria-hidden="true"
              ><Settings size={20} strokeWidth={1.7} /></span
            ><span
              ><strong>Account settings</strong><small>Manage credentials and devices</small></span
            ></span
          ><ChevronRight class="chevron" size={20} strokeWidth={1.7} aria-hidden="true" /></summary
        >
        <div class="settings-content">
          <h2>Change your sign-in</h2>
          <p>Changing your credentials signs out all connected devices within one minute.</p>
          <form onsubmit={saveAccount}>
            <label
              >Username<input
                bind:value={accountUser}
                autocomplete="username"
                required
                maxlength="64"
              /></label
            >
            <label
              >Current password<input
                bind:value={currentPass}
                type="password"
                autocomplete="current-password"
                required
                maxlength="256"
              /></label
            >
            <label
              >New password<input
                bind:value={newPass}
                type="password"
                autocomplete="new-password"
                minlength="12"
                maxlength="256"
                required
                aria-describedby="password-help"
              /><span id="password-help" class="field-help">Use at least 12 characters.</span
              ></label
            >
            <button class="primary" disabled={busy}>Save credentials</button>
          </form>
          <div class="devices">
            <h2>Connected devices</h2>
            <p>Sign out devices on every server. You stay signed in here.</p>
            <button class="secondary" disabled={busy} onclick={revokeAll}
              >Sign out all devices</button
            >
          </div>
        </div>
      </details>
    {/if}
    {#if message}<p id="message" class:error={isError} role="status" aria-live="polite">
        {message}
      </p>{/if}
  </main>
  <footer>Cloudstream <span aria-hidden="true">·</span> Account</footer>
</div>
