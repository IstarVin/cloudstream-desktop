<script lang="ts">
  import { onMount } from "svelte";
  import { api } from "#lib/api";
  import { cast } from "#lib/cast.svelte";
  import CastButton from "#lib/components/CastButton.svelte";
  import BrowserChecks from "#lib/components/BrowserChecks.svelte";
  import NowPlaying from "#lib/components/NowPlaying.svelte";
  import Pairing from "#lib/components/Pairing.svelte";
  import SourcesSheet from "#lib/components/SourcesSheet.svelte";
  import WebPlayer from "#lib/components/player/WebPlayer.svelte";
  import { bindCastDevices, playTarget } from "#lib/play-target.svelte";
  import { player } from "#lib/player.svelte";
  import { toasts } from "#lib/toast.svelte";
  import { webPlayer } from "#lib/web-player.svelte";
  import { rememberTab, restoreScroll, saveScroll, tabUrl } from "#lib/view-state";
  import { beforeNavigate, afterNavigate } from "$app/navigation";
  import { page } from "$app/state";
  import {
    CircleAlert,
    CircleCheck,
    House,
    Library,
    Play,
    Search,
    Settings2,
    X,
  } from "@lucide/svelte";
  import { QueryClient, QueryClientProvider } from "@tanstack/svelte-query";
  import "../app.css";
  import type { LayoutProps } from "./$types";

  let { children }: LayoutProps = $props();

  const client = new QueryClient({
    defaultOptions: { queries: { retry: 1, refetchOnWindowFocus: false } },
  });

  bindCastDevices();

  let authed = $state<boolean | null>(null);
  api
    .authStatus()
    .then((s) => {
      authed = s.authed;
      if (s.authed) {
        player.start();
        void playTarget.refresh();
      }
    })
    .catch(() => (authed = false));

  onMount(() => {
    const expired = () => {
      location.reload();
    };
    window.addEventListener("auth-expired", expired);
    return () => window.removeEventListener("auth-expired", expired);
  });

  const nav = [
    { href: "/", label: "Home", icon: House },
    { href: "/search", label: "Search", icon: Search },
    { href: "/library", label: "Library", icon: Library },
    { href: "/settings", label: "Settings", icon: Settings2 },
  ];

  // Native-app feel: every screen keeps its scroll position, and a tab reopens on the screen you left.
  if (typeof history !== "undefined") history.scrollRestoration = "manual";
  beforeNavigate(({ from }) => {
    if (from) saveScroll(from.url);
  });
  afterNavigate(({ to }) => {
    if (!to) return;
    const tab = nav.find((n) => isActive(n.href, to.url.pathname));
    if (tab) rememberTab(tab.href, to.url);
    restoreScroll(to.url);
  });
  const isActive = (href: string, path = page.url.pathname) =>
    href === "/" ? path === "/" : path.startsWith(href);
</script>

{#if authed === false}
  <Pairing />
{:else if authed}
  <QueryClientProvider {client}>
    <a class="skip-link" href="#main-content">Skip to content</a>
    <div class={["shell", { "with-bar": player.active }]}>
      <nav aria-label="Main">
        <a class="brand" href="/" aria-label="Cloudstream Desktop, go to Home">
          <span class="brand-icon"
            ><Play size={20} fill="currentColor" strokeWidth={1.8} aria-hidden="true" /></span
          >
          <span class="brand-name">Cloudstream<span>Desktop</span></span>
        </a>
        {#each nav as n (n.href)}
          <a
            class={[
              "nav-link",
              { active: isActive(n.href), "nav-settings": n.href === "/settings" },
            ]}
            href={isActive(n.href) ? n.href : tabUrl(n.href)}
            aria-current={isActive(n.href) ? "page" : undefined}
          >
            <span class="nav-icon"><n.icon size={22} strokeWidth={1.8} aria-hidden="true" /></span>
            <span>{n.label}</span>
            <span class="nav-indicator" aria-hidden="true"></span>
          </a>
        {/each}
      </nav>
      <main id="main-content" tabindex="-1">
        <CastButton class="cast-top" />
        <BrowserChecks />
        {@render children()}
      </main>
    </div>
    <NowPlaying />
    <WebPlayer />
    {#if cast.needsTap && webPlayer.open}
      <button class="cast-tap" onclick={() => cast.tapToPlay()}>
        <Play size={22} fill="currentColor" strokeWidth={1.8} aria-hidden="true" />
        <span
          ><b>Cast from another device</b><small
            >Tap to start: browsers need a tap before playing sound.</small
          ></span
        >
      </button>
    {/if}
    <SourcesSheet />
  </QueryClientProvider>
{:else}
  <div class="app-loading" role="status">
    <div class="spinner"></div>
    <span>Opening Cloudstream…</span>
  </div>
{/if}

<div class={["toasts", { "with-bar": player.active }]} aria-live="polite" aria-relevant="additions">
  {#each toasts.items as t (t.id)}
    <div class={["toast", { error: t.error }]}>
      <span class="toast-icon" aria-hidden="true">
        {#if t.error}<CircleAlert size={20} />{:else}<CircleCheck size={20} />{/if}
      </span>
      <p>{t.text}</p>
      <button
        class="toast-dismiss"
        aria-label="Dismiss notification"
        onclick={() => toasts.dismiss(t.id)}
      >
        <X size={18} aria-hidden="true" />
      </button>
    </div>
  {/each}
</div>

<style>
  .skip-link {
    position: fixed;
    top: 12px;
    left: 12px;
    z-index: 200;
    padding: 12px 18px;
    border-radius: var(--radius-sm);
    background: var(--accent);
    color: var(--accent-ink);
    transform: translateY(-180%);
  }
  .skip-link:focus {
    transform: translateY(0);
  }
  .app-loading {
    min-height: 100dvh;
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 12px;
    color: var(--muted);
  }
  main {
    min-width: 0;
  }
  :global(.cast-top) {
    position: fixed;
    top: calc(8px + env(safe-area-inset-top, 0px));
    right: 12px;
    z-index: 30;
    background: rgb(14 15 19 / 0.7);
    backdrop-filter: blur(10px);
    border: 1px solid var(--line);
  }
  main:focus {
    outline: none;
  }
  .shell {
    min-height: 100dvh;
    padding-bottom: calc(var(--nav-h) + var(--safe-b) + 16px);
  }
  .shell.with-bar {
    padding-bottom: calc(var(--nav-h) + var(--bar-h) + var(--safe-b) + 24px);
  }
  nav {
    position: fixed;
    left: 0;
    right: 0;
    bottom: 0;
    height: calc(var(--nav-h) + var(--safe-b));
    padding-bottom: var(--safe-b);
    display: flex;
    background: rgb(14 15 19 / 0.94);
    backdrop-filter: blur(14px);
    border-top: 1px solid var(--line);
    z-index: 40;
  }
  .nav-link {
    flex: 1;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 3px;
    position: relative;
    min-width: 0;
    transition:
      color 150ms,
      background-color 150ms;
    font-size: 11.5px;
    font-weight: 600;
    color: var(--muted);
  }
  .nav-link.active {
    color: var(--accent);
  }
  .nav-icon {
    display: grid;
    place-items: center;
    width: 40px;
    height: 28px;
    border-radius: var(--radius-sm);
  }
  .nav-link.active .nav-icon {
    background: var(--accent-soft);
  }
  .nav-link:focus-visible {
    outline: 2px solid var(--accent);
    outline-offset: -4px;
  }
  .nav-link:active {
    background: var(--surface-2);
  }
  .nav-indicator {
    display: none;
  }
  @media (hover: hover) {
    .nav-link:hover {
      color: var(--text);
      background: var(--surface-2);
    }
    .nav-link.active:hover {
      color: var(--accent);
      background: var(--accent-soft);
    }
  }
  .brand {
    display: none;
  }
  .cast-tap {
    position: fixed;
    inset: 0;
    z-index: 80;
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 14px;
    padding: 24px;
    background: rgb(0 0 0 / 0.55);
    color: var(--text);
    text-align: left;
  }
  .cast-tap span {
    display: grid;
    gap: 2px;
  }
  .cast-tap small {
    color: var(--muted);
  }
  .toasts {
    position: fixed;
    left: 50%;
    transform: translateX(-50%);
    bottom: calc(var(--nav-h) + var(--safe-b) + 16px);
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 8px;
    z-index: 100;
    width: min(calc(100% - 32px), 420px);
    pointer-events: none;
  }
  .toasts.with-bar {
    bottom: calc(var(--nav-h) + var(--bar-h) + var(--safe-b) + 16px);
  }
  .toast {
    display: flex;
    align-items: flex-start;
    gap: 10px;
    width: fit-content;
    max-width: 100%;
    padding: 6px 6px 6px 14px;
    border-radius: var(--radius);
    background: var(--surface-2);
    box-shadow: 0 8px 24px rgb(0 0 0 / 0.45);
    pointer-events: auto;
    animation: toast-in 220ms cubic-bezier(0.16, 1, 0.3, 1) both;
  }
  .toast-icon {
    display: grid;
    flex: none;
    place-items: center;
    width: 28px;
    height: 28px;
    margin-top: 8px;
    border-radius: 50%;
    background: var(--accent-soft);
    color: var(--accent);
  }
  .toast.error .toast-icon {
    background: color-mix(in srgb, var(--danger) 14%, transparent);
    color: var(--danger);
  }
  .toast p {
    flex: 1;
    min-width: 0;
    margin: 0;
    padding: 12px 0;
    color: var(--text);
    font-size: 14px;
    font-weight: 500;
    line-height: 20px;
    overflow-wrap: anywhere;
  }
  .toast-dismiss {
    display: grid;
    flex: none;
    place-items: center;
    width: 44px;
    height: 44px;
    padding: 0;
    border: 0;
    border-radius: 8px;
    background: var(--surface-3);
    color: var(--muted);
    cursor: pointer;
    transition:
      background-color 150ms,
      color 150ms;
  }
  .toast-dismiss:hover {
    background: color-mix(in srgb, var(--text) 16%, var(--surface-2));
    color: var(--text);
  }
  .toast-dismiss:active {
    background: color-mix(in srgb, var(--text) 22%, var(--surface-2));
  }
  .toast-dismiss:focus-visible {
    outline: 2px solid var(--accent);
    outline-offset: 2px;
  }
  @keyframes toast-in {
    from {
      opacity: 0;
      transform: translateY(8px);
    }
    to {
      opacity: 1;
      transform: translateY(0);
    }
  }
  @media (prefers-reduced-motion: reduce) {
    .toast {
      animation: none;
    }
    .nav-link {
      transition: none;
    }
  }
  @media (min-width: 900px) {
    .shell,
    .shell.with-bar {
      padding: 0 0 calc(var(--bar-h) + 32px) var(--rail-w);
    }
    nav {
      top: 0;
      right: auto;
      width: var(--rail-w);
      height: auto;
      flex-direction: column;
      justify-content: flex-start;
      gap: 8px;
      padding: 24px 12px 16px;
      overflow-y: auto;
      background: var(--surface);
      backdrop-filter: none;
      border-top: 0;
      border-right: 1px solid var(--line);
    }
    .nav-link {
      flex: 0 0 52px;
      flex-direction: row;
      justify-content: flex-start;
      gap: 12px;
      padding: 0 14px;
      margin: 0;
      border-radius: var(--radius-sm);
      font-size: 14px;
    }
    .nav-link.active {
      background: var(--accent-soft);
    }
    .nav-icon {
      width: 24px;
      height: 24px;
      flex: none;
    }
    .nav-link.active .nav-icon {
      background: transparent;
    }
    .nav-indicator {
      display: block;
      width: 5px;
      height: 5px;
      margin-left: auto;
      border-radius: 50%;
      background: currentColor;
      visibility: hidden;
    }
    .nav-link.active .nav-indicator {
      visibility: visible;
    }
    .nav-settings {
      margin-top: auto;
    }
    .nav-settings::before {
      content: "";
      position: absolute;
      top: -12px;
      left: 14px;
      right: 14px;
      height: 1px;
      background: var(--line);
    }
    .nav-link:nth-last-child(2) {
      margin-bottom: 32px;
    }
    .brand {
      display: flex;
      align-items: center;
      gap: 11px;
      padding: 4px 12px 28px;
      flex-shrink: 0;
      color: inherit;
      text-decoration: none;
      border-radius: 10px;
    }
    .brand:focus-visible {
      outline: 2px solid var(--accent);
      outline-offset: 2px;
    }
    .brand-icon {
      display: grid;
      place-items: center;
      width: 36px;
      height: 40px;
      color: var(--accent);
    }
    .brand-name {
      font-size: 17px;
      font-weight: 750;
      letter-spacing: -0.025em;
      line-height: 1.4;
    }
    .brand-name > span {
      display: block;
      color: var(--muted);
      font-size: 11px;
      font-weight: 500;
      letter-spacing: 0.04em;
    }
    .toasts {
      bottom: 24px;
    }
    .toasts.with-bar {
      bottom: calc(var(--bar-h) + 24px);
    }
  }
</style>
