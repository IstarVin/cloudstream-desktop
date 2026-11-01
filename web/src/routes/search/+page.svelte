<script lang="ts">
  import { page } from "$app/state";
  import { goto } from "$app/navigation";
  import { untrack } from "svelte";
  import { streamSearch } from "#lib/api";
  import Icon from "#lib/components/Icon.svelte";
  import Poster from "#lib/components/Poster.svelte";
  import type { SearchItem } from "#lib/types";

  let query = $state(page.url.searchParams.get("q") ?? "");
  let results = $state<{ provider: string; items: SearchItem[] }[]>([]);
  let errors = $state<{ provider: string; error: string }[]>([]);
  let searching = $state(false);
  let type = $state<string | null>(null);
  let stop: (() => void) | null = null;

  const TYPE_GROUPS: Record<string, string[]> = {
    Movies: ["Movie", "AnimeMovie"],
    Series: ["TvSeries", "AsianDrama", "Cartoon", "Documentary"],
    Anime: ["Anime", "OVA", "AnimeMovie"],
    Live: ["Live"],
  };
  const matches = (i: SearchItem) => !type || TYPE_GROUPS[type].includes(i.type ?? "");
  const visible = $derived(results.map((r) => ({ ...r, items: r.items.filter(matches) })).filter((r) => r.items.length));

  function run(q: string) {
    stop?.();
    results = [];
    errors = [];
    if (!q.trim()) return;
    searching = true;
    stop = streamSearch(q.trim(), {
      results: (provider, items) => {
        if (items.length) results.push({ provider, items });
      },
      error: (provider, error) => errors.push({ provider, error }),
      done: () => (searching = false),
    });
  }

  // The URL holds the query (so back/forward and reloads work); changing it starts the search.
  function submit(e: SubmitEvent) {
    e.preventDefault();
    const q = query.trim();
    if (page.url.searchParams.get("q") === q) run(q);
    else goto(`/search?q=${encodeURIComponent(q)}`, { replaceState: true });
  }

  $effect(() => {
    const q = page.url.searchParams.get("q");
    if (q) untrack(() => run(q));
    return () => stop?.();
  });
</script>

<div class="page">
  <form class="box" onsubmit={submit} role="search">
    <Icon name="search" size={20} />
    <!-- svelte-ignore a11y_autofocus -->
    <input type="search" placeholder="Search every provider" bind:value={query} autofocus enterkeyhint="search" aria-label="Search" />
    {#if searching}<div class="spinner"></div>{/if}
  </form>

  <div class="scroll-x filters">
    <button class="chip" class:on={type === null} onclick={() => (type = null)}>All</button>
    {#each Object.keys(TYPE_GROUPS) as t (t)}
      <button class="chip" class:on={type === t} onclick={() => (type = type === t ? null : t)}>{t}</button>
    {/each}
  </div>

  {#each visible as r (r.provider)}
    <h2 class="section-title">{r.provider} <span class="count">{r.items.length}</span></h2>
    <div class="scroll-x">
      {#each r.items as item (item.url)}
        <Poster provider={item.provider} url={item.url} name={item.name} poster={item.poster} meta={[item.year, item.type].filter(Boolean).join(" · ")} />
      {/each}
    </div>
  {/each}

  {#if !searching && page.url.searchParams.get("q") && !visible.length}
    <div class="empty">Nothing found{type ? ` in ${type}` : ""}.</div>
  {/if}

  {#if errors.length && !searching}
    <details class="errors">
      <summary>{errors.length} providers failed</summary>
      {#each errors as e (e.provider)}<div><b>{e.provider}</b>: {e.error}</div>{/each}
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
    border-radius: 999px;
    padding: 0 16px;
    color: var(--muted);
  }
  .box:focus-within {
    border-color: var(--accent);
  }
  .box input {
    flex: 1;
    border: 0;
    background: none;
    height: 48px;
    padding: 0;
    font-size: 16px;
  }
  .filters {
    margin: 12px -16px 0;
    padding: 0 16px;
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
  }
</style>
