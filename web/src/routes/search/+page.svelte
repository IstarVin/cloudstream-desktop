<script lang="ts">
  import { streamSearch } from "#lib/api";
  import Poster from "#lib/components/Poster.svelte";
  import type { SearchItem } from "#lib/types";
  import { goto } from "$app/navigation";
  import { page } from "$app/state";
  import { RefreshCw, Search } from "@lucide/svelte";
  import { untrack } from "svelte";

  let query = $derived(page.url.searchParams.get("q") ?? "");
  let results = $state<{ provider: string; items: SearchItem[] }[]>([]);
  let errors = $state<{ provider: string; error: string }[]>([]);
  let searching = $state(false);
  let type = $state<string | null>(null);
  let stop: (() => void) | null = null;
  const provider = $derived(page.url.searchParams.get("provider") || "");

  const TYPE_GROUPS: Record<string, string[]> = {
    Movies: ["Movie", "AnimeMovie"],
    Series: ["TvSeries", "AsianDrama", "Cartoon", "Documentary"],
    Anime: ["Anime", "OVA", "AnimeMovie"],
    Live: ["Live"],
  };
  const matches = (i: SearchItem) => !type || TYPE_GROUPS[type].includes(i.type ?? "");
  const visible = $derived(
    results.map((r) => ({ ...r, items: r.items.filter(matches) })).filter((r) => r.items.length),
  );
  const resultCount = $derived(visible.reduce((total, group) => total + group.items.length, 0));
  const submittedQuery = $derived((page.url.searchParams.get("q") ?? "").trim());

  function run(q: string, source: string) {
    stop?.();
    results = [];
    errors = [];
    searching = false;
    if (!q.trim()) return;
    searching = true;
    stop = streamSearch(
      q.trim(),
      {
        results: (provider, items) => {
          if (items.length) results.push({ provider, items });
        },
        error: (provider, error) => errors.push({ provider, error }),
        done: () => (searching = false),
      },
      source || undefined,
    );
  }

  // The URL holds the query (so back/forward and reloads work); changing it starts the search.
  function submit(e: SubmitEvent) {
    e.preventDefault();
    const q = query.trim();
    if (page.url.searchParams.get("q") === q) run(q, provider);
    else {
      const params = new URLSearchParams();
      if (provider) params.set("provider", provider);
      if (q) params.set("q", q);
      goto(`/search?${params}`, { replace: true });
    }
  }

  $effect(() => {
    const q = page.url.searchParams.get("q") ?? "";
    const source = provider;
    untrack(() => run(q, source));
    return () => stop?.();
  });
</script>

<div class="page">
  <div class="page-heading">
    <h1 class="page-title">Search</h1>
    <p>
      {provider ? `Find a title on ${provider}.` : "Find a title across your providers."}
    </p>
  </div>
  <form class="box" onsubmit={submit} role="search">
    <Search size={20} strokeWidth={1.8} aria-hidden="true" />
    <input
      type="search"
      placeholder={provider ? `Search ${provider}` : "Search every provider"}
      bind:value={query}
      enterkeyhint="search"
      aria-label="Search"
    />
    <button class="search-submit" type="submit" disabled={!query.trim()} aria-label="Submit search">
      <Search size={18} strokeWidth={1.8} aria-hidden="true" />
      <span>Search</span>
    </button>
  </form>

  <div class="scroll-x filters" role="group" aria-label="Filter search results">
    <button
      class="chip"
      class:on={type === null}
      aria-pressed={type === null}
      onclick={() => (type = null)}>All</button
    >
    {#each Object.keys(TYPE_GROUPS) as t (t)}
      <button
        class="chip"
        class:on={type === t}
        aria-pressed={type === t}
        onclick={() => (type = type === t ? null : t)}>{t}</button
      >
    {/each}
  </div>

  <div class="search-status" role="status" aria-atomic="true">
    {#if submittedQuery}
      {#if searching}<span class="spinner" aria-hidden="true"></span>{/if}
      <span
        >{searching
          ? `Searching for “${submittedQuery}”…`
          : `${resultCount} ${resultCount === 1 ? "result" : "results"} for “${submittedQuery}”`}</span
      >
    {/if}
  </div>

  {#each visible as r (r.provider)}
    <h2 class="section-title">
      {r.provider} <span class="count">{r.items.length}</span>
    </h2>
    <div class="scroll-x">
      {#each r.items as item (item.url)}
        <Poster
          provider={item.provider}
          url={item.url}
          name={item.name}
          poster={item.poster}
          meta={[item.year, item.type].filter(Boolean).join(" · ")}
        />
      {/each}
    </div>
  {/each}

  {#if !searching && !page.url.searchParams.get("q")}
    <div class="empty search-empty">
      <Search size={36} strokeWidth={1.8} aria-hidden="true" />
      <h2>What are you in the mood for?</h2>
      <p>Search for a movie, series, or anime to get started.</p>
    </div>
  {/if}

  {#if !searching && submittedQuery && !visible.length}
    <div class="empty search-empty">
      <Search size={32} strokeWidth={1.8} aria-hidden="true" />
      <h2>
        {errors.length && !results.length ? "Search couldn’t finish" : "No matching titles"}
      </h2>
      <p>
        {type && results.length
          ? `Try another content type or show all results.`
          : errors.length && !results.length
            ? "A provider couldn’t be reached. Try your search again."
            : "Try a different title or check the spelling."}
      </p>
      {#if type}
        <button class="btn" onclick={() => (type = null)}>Show all results</button>
      {:else if errors.length}
        <button class="btn" onclick={() => run(submittedQuery, provider)}
          ><RefreshCw size={16} aria-hidden="true" /> Try again</button
        >
      {/if}
    </div>
  {/if}

  {#if errors.length && !searching}
    <details class="errors">
      <summary
        >{errors.length}
        {errors.length === 1 ? "provider" : "providers"} failed</summary
      >
      {#each errors as e (e.provider)}<div>
          <b>{e.provider}</b>: {e.error}
        </div>{/each}
    </details>
  {/if}
</div>

<style>
  .box {
    display: flex;
    align-items: center;
    gap: 10px;
    background: var(--surface-2);
    border: 1px solid var(--line);
    border-radius: var(--radius);
    padding: 6px 6px 6px 16px;
    color: var(--muted);
  }
  .box:focus-within {
    border-color: var(--accent);
    outline: 2px solid var(--accent);
    outline-offset: 3px;
  }
  .box input:focus-visible {
    outline: none;
  }
  .box input {
    flex: 1;
    border: 0;
    background: none;
    min-width: 0;
    width: 100%;
    height: 44px;
    padding: 0;
    font-size: 16px;
  }
  .filters {
    margin-top: 16px;
    gap: 8px;
  }
  .search-submit {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 8px;
    flex-shrink: 0;
    min-height: 44px;
    padding-inline: 16px;
    border-radius: var(--radius-sm);
    background: var(--accent);
    color: var(--accent-ink);
    font-weight: 650;
    font-size: 14px;
  }
  .search-submit:hover:not(:disabled) {
    filter: brightness(1.06);
  }
  .search-submit:disabled {
    background: var(--surface-3);
    color: var(--muted);
    cursor: default;
  }
  .search-status {
    display: flex;
    align-items: center;
    gap: 10px;
    margin-top: 16px;
    color: var(--muted);
    font-size: 13px;
    overflow-wrap: anywhere;
  }
  .search-empty {
    max-width: 480px;
    margin-inline: auto;
  }
  .search-empty h2 {
    color: var(--text);
    font-size: 20px;
    margin-top: 18px;
  }
  .search-empty p {
    font-size: 14px;
  }
  .count {
    color: var(--faint);
    font-weight: 500;
    font-size: 14px;
  }
  .errors {
    margin-top: 28px;
    font-size: 13px;
    color: var(--muted);
  }
  .errors summary {
    cursor: pointer;
    margin-bottom: 6px;
    min-height: 44px;
    padding-block: 12px;
  }
  .errors div {
    overflow-wrap: anywhere;
  }
  @media (max-width: 599px) {
    .search-submit {
      width: 44px;
      padding: 0;
    }
    .search-submit span {
      display: none;
    }
    .filters {
      flex-wrap: wrap;
      overflow: visible;
    }
  }
</style>
