<script lang="ts">
  import "../app.css";
  import { page } from "$app/state";
  import { QueryClient, QueryClientProvider } from "@tanstack/svelte-query";
  import { api } from "#lib/api";
  import BrowserChecks from "#lib/components/BrowserChecks.svelte";
  import Icon, { type IconName } from "#lib/components/Icon.svelte";
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

  const nav: { href: string; label: string; icon: IconName }[] = [
    { href: "/", label: "Home", icon: "home" },
    { href: "/search", label: "Search", icon: "search" },
    { href: "/library", label: "Library", icon: "library" },
    { href: "/settings", label: "Settings", icon: "settings" },
  ];
  const isActive = (href: string) => (href === "/" ? page.url.pathname === "/" : page.url.pathname.startsWith(href));
</script>

{#if authed === false}
  <Pairing />
{:else if authed}
  <QueryClientProvider {client}>
    <div class="shell" class:with-bar={player.active}>
      <nav aria-label="Main">
        <div class="brand" aria-hidden="true"><Icon name="play" size={18} fill /></div>
        {#each nav as n (n.href)}
          <a href={n.href} class:active={isActive(n.href)} aria-current={isActive(n.href) ? "page" : undefined}>
            <Icon name={n.icon} />
            <span>{n.label}</span>
          </a>
        {/each}
      </nav>
      <main>
        <BrowserChecks />
        {@render children()}
      </main>
    </div>
    <NowPlaying />
    <SourcesSheet />
  </QueryClientProvider>
{/if}

<div class="toasts" aria-live="polite">
  {#each toasts.items as t (t.id)}
    <div class="toast" class:error={t.error}>{t.text}</div>
  {/each}
</div>

<style>
  .shell {
    min-height: 100vh;
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
  nav a {
    flex: 1;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 3px;
    font-size: 11.5px;
    font-weight: 600;
    color: var(--faint);
  }
  nav a.active {
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
    nav a {
      flex: 0 0 64px;
      margin: 0 10px;
      border-radius: 12px;
    }
    nav a:hover {
      background: var(--surface);
    }
    .brand {
      display: grid;
      place-items: center;
      width: 40px;
      height: 40px;
      margin: 0 auto 14px;
      border-radius: 12px;
      background: var(--accent);
      color: var(--accent-ink);
    }
    .toasts {
      bottom: calc(var(--bar-h) + 32px);
    }
  }
</style>
