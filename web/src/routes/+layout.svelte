<script lang="ts">
  import "../app.css";
  import { page } from "$app/state";
  import { QueryClient, QueryClientProvider } from "@tanstack/svelte-query";
  import { api } from "#lib/api";
  import BrowserChecks from "#lib/components/BrowserChecks.svelte";
  import { House, Library, Play, Search, Settings2 } from "@lucide/svelte";
  import NowPlaying from "#lib/components/NowPlaying.svelte";
  import Pairing from "#lib/components/Pairing.svelte";
  import SourcesSheet from "#lib/components/SourcesSheet.svelte";
  import { player } from "#lib/player.svelte";
  import { toasts } from "#lib/toast.svelte";
  import type { LayoutProps } from "./$types";

  let { children }: LayoutProps = $props();

  const client = new QueryClient({
    defaultOptions: { queries: { retry: 1, refetchOnWindowFocus: false } },
  });

  let authed = $state<boolean | null>(null);
  api
    .authStatus()
    .then((s) => {
      authed = s.authed;
      if (s.authed) player.start();
    })
    .catch(() => (authed = false));

  const nav = [
    { href: "/", label: "Home", icon: House },
    { href: "/search", label: "Search", icon: Search },
    { href: "/library", label: "Library", icon: Library },
    { href: "/settings", label: "Settings", icon: Settings2 },
  ];
  const isActive = (href: string) => (href === "/" ? page.url.pathname === "/" : page.url.pathname.startsWith(href));
</script>

{#if authed === false}
  <Pairing />
{:else if authed}
  <QueryClientProvider {client}>
    <a class="skip-link" href="#main-content">Skip to content</a>
    <div class="shell" class:with-bar={player.active}>
      <nav aria-label="Main">
        <div class="brand">
          <span class="brand-icon"><Play size={20} fill="currentColor" strokeWidth={1.8} aria-hidden="true" /></span>
          <span class="brand-name">Cloudstream<span>Desktop</span></span>
        </div>
        {#each nav as n (n.href)}
          <a class="nav-link" href={n.href} class:active={isActive(n.href)} aria-current={isActive(n.href) ? "page" : undefined}>
            <n.icon size={22} strokeWidth={1.8} aria-hidden="true" />
            <span>{n.label}</span>
          </a>
        {/each}
      </nav>
      <main id="main-content" tabindex="-1">
        <BrowserChecks />
        {@render children()}
      </main>
    </div>
    <NowPlaying />
    <SourcesSheet />
  </QueryClientProvider>
{:else}
  <div class="app-loading" role="status"><div class="spinner"></div><span>Opening Cloudstream…</span></div>
{/if}

<div class="toasts" aria-live="polite">
  {#each toasts.items as t (t.id)}
    <div class="toast" class:error={t.error}>{t.text}</div>
  {/each}
</div>

<style>
  .skip-link { position: fixed; top: 12px; left: 12px; z-index: 200; padding: 12px 18px; border-radius: var(--radius-sm); background: var(--accent); color: var(--accent-ink); transform: translateY(-180%); }
  .skip-link:focus { transform: translateY(0); }
  .app-loading { min-height: 100dvh; display: flex; align-items: center; justify-content: center; gap: 12px; color: var(--muted); }
  main { min-width: 0; }
  main:focus { outline: none; }
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
    font-size: 11.5px;
    font-weight: 600;
    color: var(--muted);
  }
  .nav-link.active {
    color: var(--accent);
  }
  .brand {
    display: none;
  }
  .toasts {
    position: fixed;
    left: 50%;
    transform: translateX(-50%);
    bottom: calc(var(--nav-h) + var(--bar-h) + var(--safe-b) + 24px);
    display: flex;
    flex-direction: column;
    gap: 8px;
    z-index: 100;
    width: min(92vw, 420px);
    pointer-events: none;
  }
  .toast {
    background: var(--surface-3);
    border: 1px solid var(--line);
    padding: 10px 14px;
    border-radius: 10px;
    font-size: 14px;
    box-shadow: 0 8px 24px rgb(0 0 0 / 0.4);
  }
  .toast.error {
    border-color: rgb(255 97 112 / 0.5);
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
      gap: 6px;
      padding: 16px 0;
      border-top: 0;
      border-right: 1px solid var(--line);
    }
    .nav-link {
      flex: 0 0 48px;
      flex-direction: row;
      justify-content: flex-start;
      gap: 14px;
      padding: 0 16px;
      margin: 0 16px;
      border-radius: var(--radius-sm);
      font-size: 14px;
    }
    .nav-link.active { background: var(--accent-soft); }
    .nav-link:hover {
      background: var(--surface);
    }
    .brand { display: flex; align-items: center; gap: 11px; padding: 12px 24px 32px; }
    .brand-icon { display: grid; place-items: center; width: 36px; height: 40px; color: var(--accent); }
    .brand-name { font-size: 17px; font-weight: 750; letter-spacing: -0.025em; line-height: 1.4; }
    .brand-name > span { display: block; color: var(--muted); font-size: 11px; font-weight: 500; letter-spacing: 0.04em; }
    .toasts {
      bottom: calc(var(--bar-h) + 32px);
    }
  }
</style>
