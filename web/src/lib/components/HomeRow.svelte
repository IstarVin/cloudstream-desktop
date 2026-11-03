<script lang="ts">
  import HomeShelf from "./HomeShelf.svelte";
  import { createInfiniteQuery } from "@tanstack/svelte-query";
  import { homePagesOptions, mergeHomeRows } from "../home";
  import type { HomeSection } from "../types";
  import InfiniteLoader from "./InfiniteLoader.svelte";
  import LoadingSkeleton from "./LoadingSkeleton.svelte";
  import Poster from "./Poster.svelte";

  let { provider, section }: { provider: string; section: HomeSection } = $props();

  // Fetch the first page only when the section approaches the viewport.
  let visible = $state(false);
  function observeRow(element: HTMLElement) {
    const io = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) {
          visible = true;
          io.disconnect();
        }
      },
      { rootMargin: "400px" },
    );
    io.observe(element);
    return () => io.disconnect();
  }

  const row = createInfiniteQuery(() => ({
    ...homePagesOptions(provider, section.index),
    enabled: visible,
  }));
  const rows = $derived(mergeHomeRows(row.data?.pages ?? []));

  function loadMore() {
    if (row.hasNextPage && !row.isFetching) void row.fetchNextPage({ cancelRefetch: false });
  }
</script>

<section {@attach observeRow}>
  {#if row.data}
    {#each rows as r (r.name)}
      {#if r.items.length}
        <HomeShelf
          title={r.name || section.name}
          href={`/home-section?${new URLSearchParams({ provider, section: String(section.index), row: r.name, name: r.name || section.name })}`}
        >
          {#each r.items as item (JSON.stringify([item.provider, item.url]))}
            <Poster
              provider={item.provider}
              url={item.url}
              name={item.name}
              poster={item.poster}
              horizontal={r.horizontal}
              meta={[item.year, item.quality].filter(Boolean).join(" · ")}
            />
          {/each}
          <InfiniteLoader
            hasNext={row.hasNextPage}
            loading={row.isFetching}
            error={row.isFetchNextPageError ? row.error?.message : null}
            horizontal
            onloadmore={loadMore}
          />
        </HomeShelf>
      {/if}
    {/each}
  {:else}
    <h2 class="section-title">{section.name}</h2>
    {#if row.isError}
      <div class="row-error">
        <p class="muted">Couldn't load this row: {row.error?.message}</p>
        <button class="btn" onclick={() => row.refetch()}>Try again</button>
      </div>
    {:else}
      <LoadingSkeleton horizontal={section.horizontal} />
    {/if}
  {/if}
</section>

<style>
  .row-error {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 16px;
    padding: 20px;
    background: var(--surface);
    border-radius: var(--radius);
  }
  .row-error p {
    flex: 1;
    margin: 0;
    overflow-wrap: anywhere;
  }
</style>
