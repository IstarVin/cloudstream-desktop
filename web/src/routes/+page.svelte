<script lang="ts">
  import { createQuery } from "@tanstack/svelte-query";
  import { api, titleHref } from "#lib/api";
  import { Layers, Search, Settings2 } from "@lucide/svelte";
  import HomeRow from "#lib/components/HomeRow.svelte";
  import LoadingSkeleton from "#lib/components/LoadingSkeleton.svelte";
  import Poster from "#lib/components/Poster.svelte";
  import ProviderPicker from "#lib/components/ProviderPicker.svelte";
  import { matchesContentType } from "#lib/content-types";
  import { homeFilter } from "#lib/view-state";

  const providers = createQuery(() => ({
    queryKey: ["providers"],
    queryFn: api.providers,
    staleTime: 30_000,
  }));
  const status = createQuery(() => ({
    queryKey: ["status"],
    queryFn: api.status,
    refetchInterval: 4000,
  }));
  const continueQ = createQuery(() => ({ queryKey: ["continue"], queryFn: api.continueWatching }));

  const withHome = $derived((providers.data ?? []).filter((p) => p.hasMainPage));
  let chosen = $state<string | null>(read());
  let contentType = $state(homeFilter.contentType);
  const matching = $derived(withHome.filter((p) => matchesContentType(p.types, contentType)));
  const current = $derived(matching.find((p) => p.name === chosen) ?? matching[0]);

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

  function filter(type: string) {
    contentType = type;
    homeFilter.contentType = type;
    // Keep the selected source when it supports the filter; otherwise switch to a compatible one.
    const next =
      withHome.find((p) => p.name === chosen && matchesContentType(p.types, type)) ??
      withHome.find((p) => matchesContentType(p.types, type));
    if (next) choose(next.name);
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
    <div class="page-heading">
      <h1 class="page-title">Home</h1>
      <p>Your next watch starts here.</p>
    </div>
    {#if current}
      <form class="browse-search" action="/search" role="search">
        <Search size={19} strokeWidth={1.8} aria-hidden="true" />
        <input type="hidden" name="provider" value={current.name} />
        <input
          type="search"
          name="q"
          aria-label={`Search ${current.name}`}
          placeholder={`Search ${current.name}`}
          enterkeyhint="search"
          required
        />
        <button type="submit" aria-label="Submit search">Search</button>
      </form>
    {/if}
    {#if status.data?.building}<span class="building"
        ><span class="spinner"></span> Building plugins…</span
      >{/if}
  </div>

  {#if providers.isPending}
    <LoadingSkeleton variant="home" />
  {:else if providers.isError}
    <div class="empty">
      <p>Couldn't load sources: {providers.error.message}</p>
      <button class="btn" onclick={() => providers.refetch()}>Try again</button>
    </div>
  {:else if !withHome.length}
    <div class="empty">
      <Layers size={32} strokeWidth={1.8} aria-hidden="true" />
      <p>No providers loaded yet.</p>
      <p class="muted">
        Plugins build in the background on first start. Check the plugin settings for errors.
      </p>
      <a class="btn" href="/settings"
        ><Settings2 size={17} strokeWidth={1.8} aria-hidden="true" /> Open settings</a
      >
    </div>
  {:else}
    <ProviderPicker
      providers={withHome}
      {current}
      {contentType}
      onchoose={choose}
      onfilter={filter}
    />
    {#if !current}
      <div class="empty">
        <p>No sources available for this content type.</p>
        <button class="btn" onclick={() => filter("all")}>Show all sources</button>
      </div>
    {/if}

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
        {:else}
          <LoadingSkeleton variant="home" showProviders={false} />
        {/if}
      {/key}
    {/if}
  {/if}
</div>

<style>
  .top {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    margin-bottom: 24px;
    justify-content: space-between;
    gap: 12px;
  }
  .page-heading {
    margin-bottom: 0;
  }
  .browse-search {
    display: flex;
    align-items: center;
    gap: 10px;
    width: min(100%, 380px);
    min-height: 48px;
    margin-left: auto;
    padding: 4px 4px 4px 14px;
    color: var(--muted);
    background: var(--surface-2);
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
  }
  .browse-search:focus-within {
    border-color: var(--accent);
    outline: 2px solid var(--accent);
    outline-offset: 2px;
  }
  .browse-search input {
    flex: 1;
    width: 100%;
    min-width: 0;
    padding: 0;
    border: 0;
    outline: 0;
    background: transparent;
  }
  .browse-search input:focus-visible {
    outline: 0;
  }
  .browse-search button {
    min-height: 38px;
    padding: 0 12px;
    border-radius: 5px;
    color: var(--text);
    font-size: 13px;
    font-weight: 600;
  }
  .browse-search button:hover {
    background: var(--surface-3);
  }
  @media (max-width: 599px) {
    .browse-search {
      width: 100%;
      margin-left: 0;
    }
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
</style>
