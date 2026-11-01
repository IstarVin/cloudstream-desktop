<script lang="ts">
  import { page } from "$app/state";
  import { createInfiniteQuery, useQueryClient } from "@tanstack/svelte-query";
  import { ArrowLeft } from "@lucide/svelte";
  import { api } from "#lib/api";
  import type { HomePage, SearchItem } from "#lib/types";
  import Poster from "#lib/components/Poster.svelte";
  import LoadingSkeleton from "#lib/components/LoadingSkeleton.svelte";

  const client = useQueryClient();
  const provider = $derived(page.url.searchParams.get("provider") ?? "");
  const sectionParam = $derived(page.url.searchParams.get("section"));
  const section = $derived(Number(sectionParam));
  const rowName = $derived(page.url.searchParams.get("row") ?? "");
  const name = $derived(page.url.searchParams.get("name") || rowName || "Titles");
  const valid = $derived(!!provider && sectionParam !== null && /^\d+$/.test(sectionParam) && Number.isSafeInteger(section));

  const titles = createInfiniteQuery(() => ({
    queryKey: ["home-pages", provider, section],
    queryFn: ({ pageParam }) => api.homeRow(provider, section, pageParam),
    initialPageParam: 1,
    getNextPageParam: (lastPage, _pages, lastPageParam) =>
      lastPage.hasNext && lastPage.rows.some((row) => row.items.length) ? lastPageParam + 1 : undefined,
    enabled: valid,
    staleTime: 5 * 60_000,
    initialData: () => {
      const cached = client.getQueryData<HomePage>(["home", provider, section]);
      return cached ? { pages: [cached], pageParams: [1] } : undefined;
    },
    initialDataUpdatedAt: () => client.getQueryState(["home", provider, section])?.dataUpdatedAt,
  }));

  const rows = $derived(titles.data?.pages.flatMap((p) => p.rows.filter((r) => r.name === rowName)) ?? []);
  const horizontal = $derived(rows[0]?.horizontal ?? false);
  const items = $derived.by(() => {
    const unique = new Map<string, SearchItem>();
    for (const row of rows) {
      for (const item of row.items) {
        const key = JSON.stringify([item.provider, item.url]);
        if (!unique.has(key)) unique.set(key, item);
      }
    }
    return [...unique.values()];
  });

  function loadMore() {
    if (titles.hasNextPage && !titles.isFetching) void titles.fetchNextPage();
  }
</script>

<svelte:head>
  <title>{name} · Cloudstream</title>
</svelte:head>

<div class="page">
  <a class="back" href="/"><ArrowLeft size={18} strokeWidth={1.8} aria-hidden="true" /> Back to home</a>
  <div class="page-heading">
    <h1 class="page-title">{name}</h1>
    <p>{provider}</p>
  </div>

  {#if !valid}
    <p class="muted">This section link is incomplete. Return home and choose “View all” from a section.</p>
  {:else if titles.isPending}
    <LoadingSkeleton />
  {:else if titles.isError && !titles.data}
    <div class="empty">
      <p>Couldn't load this section: {titles.error?.message}</p>
      <button class="btn" onclick={() => titles.refetch()}>Try again</button>
    </div>
  {:else}
    <div class={["poster-grid", { horizontal }]} aria-busy={titles.isFetchingNextPage}>
      {#each items as item (JSON.stringify([item.provider, item.url]))}
        <Poster
          provider={item.provider}
          url={item.url}
          name={item.name}
          poster={item.poster}
          {horizontal}
          meta={[item.year, item.quality].filter(Boolean).join(" · ")}
          --card-w="100%"
          --card-w-h="100%"
        />
      {/each}
    </div>

    <div class="pagination">
      <p class="muted" role="status">
        {#if titles.isFetchingNextPage}
          Loading more titles…
        {:else if !items.length}
          No titles in this section yet.
        {:else}
          {items.length} {items.length === 1 ? "title" : "titles"}{titles.hasNextPage ? " loaded" : " · All loaded"}
        {/if}
      </p>
      {#if titles.isFetchNextPageError}
        <p class="load-error" role="alert">Couldn't load more titles: {titles.error?.message}</p>
      {/if}
      {#if titles.hasNextPage}
        <button class="btn" onclick={loadMore} disabled={titles.isFetching}>
          {#if titles.isFetchingNextPage}<span class="spinner" aria-hidden="true"></span>{/if}
          {titles.isFetchingNextPage ? "Loading…" : titles.isFetchNextPageError ? "Try again" : "Load more"}
        </button>
      {/if}
    </div>
  {/if}
</div>

<style>
  .back {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    min-height: 44px;
    margin-bottom: 16px;
    color: var(--muted);
  }
  .back:hover { color: var(--text); }
  .poster-grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(min(100%, 132px), 1fr));
    gap: 24px 16px;
  }
  .poster-grid.horizontal {
    grid-template-columns: repeat(auto-fill, minmax(min(100%, 220px), 1fr));
  }
  .pagination {
    margin-top: 32px;
    text-align: center;
  }
  .pagination p { margin: 0 0 16px; }
  .load-error { color: var(--danger); }
  @media (min-width: 900px) {
    .poster-grid { grid-template-columns: repeat(auto-fill, minmax(164px, 1fr)); }
    .poster-grid.horizontal { grid-template-columns: repeat(auto-fill, minmax(272px, 1fr)); }
  }
</style>
