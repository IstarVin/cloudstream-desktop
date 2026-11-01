<script lang="ts">
  import { createQuery } from "@tanstack/svelte-query";
  import { api, titleHref } from "#lib/api";
  import HomeRow from "#lib/components/HomeRow.svelte";
  import Poster from "#lib/components/Poster.svelte";

  const providers = createQuery(() => ({ queryKey: ["providers"], queryFn: api.providers, staleTime: 30_000 }));
  const status = createQuery(() => ({ queryKey: ["status"], queryFn: api.status, refetchInterval: 4000 }));
  const continueQ = createQuery(() => ({ queryKey: ["continue"], queryFn: api.continueWatching }));

  const withHome = $derived((providers.data ?? []).filter((p) => p.hasMainPage));
  let chosen = $state<string | null>(read());
  const current = $derived(withHome.find((p) => p.name === chosen) ?? withHome[0]);

  function read() {
    try {
      return localStorage.getItem("home-provider");
    } catch {
      return null;
    }
  }
  function choose(name: string) {
    chosen = name;
    try {
      localStorage.setItem("home-provider", name);
    } catch {
      // Private mode: the choice just isn't remembered.
    }
  }

  const sections = createQuery(() => ({
    queryKey: ["home-sections", current?.name],
    queryFn: () => api.homeSections(current!.name),
    enabled: !!current,
    staleTime: 10 * 60_000,
  }));
</script>

<div class="page">
  <div class="top">
    <h1 class="page-title">Home</h1>
    {#if status.data?.building}<span class="building"><span class="spinner"></span> Building plugins…</span>{/if}
  </div>

  {#if providers.isPending}
    <div class="empty"><div class="spinner" style="margin: 0 auto"></div></div>
  {:else if !withHome.length}
    <div class="empty">
      <p>No providers loaded yet.</p>
      <p class="muted">Plugins build in the background on first start. Check Settings → Plugins for errors.</p>
    </div>
  {:else}
    <div class="scroll-x pickers" role="tablist" aria-label="Provider">
      {#each withHome as p (p.name)}
        <button class="chip" class:on={p.name === current?.name} role="tab" aria-selected={p.name === current?.name} onclick={() => choose(p.name)}>
          {#if p.iconUrl}<img src={p.iconUrl} alt="" width="16" height="16" referrerpolicy="no-referrer" />{/if}
          {p.name}
        </button>
      {/each}
    </div>

    {#if continueQ.data?.length}
      <h2 class="section-title">Continue watching</h2>
      <div class="scroll-x">
        {#each continueQ.data as c (c.provider + c.url)}
          <Poster
            provider={c.provider}
            url={c.url}
            name={c.title}
            poster={c.poster}
            meta={c.episodeLabel ?? ""}
            progress={c.duration ? c.position / c.duration : null}
            href={titleHref(c.provider, c.url)}
            showProvider
          />
        {/each}
      </div>
    {/if}

    {#if current}
      {#key current.name}
        {#if sections.data}
          {#each sections.data as s (s.index)}
            <HomeRow provider={current.name} section={s} />
          {/each}
        {:else if sections.isError}
          <p class="muted">{sections.error?.message}</p>
        {/if}
      {/key}
    {/if}
  {/if}
</div>

<style>
  .top {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 12px;
  }
  .building {
    display: inline-flex;
    gap: 8px;
    align-items: center;
    font-size: 13px;
    color: var(--muted);
  }
  .building .spinner {
    width: 14px;
    height: 14px;
  }
  .pickers {
    margin: 0 -16px;
    padding: 0 16px 4px;
  }
  .pickers img {
    border-radius: 4px;
  }
</style>
