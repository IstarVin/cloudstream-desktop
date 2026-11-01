<script lang="ts">
  import { createInfiniteQuery } from "@tanstack/svelte-query";
  import { ChevronRight } from "@lucide/svelte";
  import { homePagesOptions, mergeHomeRows } from "../home";
  import type { HomeSection } from "../types";
  import Poster from "./Poster.svelte";
  import LoadingSkeleton from "./LoadingSkeleton.svelte";
  import InfiniteLoader from "./InfiniteLoader.svelte";

  let { provider, section }: { provider: string; section: HomeSection } = $props();

  // Fetch the first page only when the section approaches the viewport.
  let visible = $state(false);
  function observeRow(element: HTMLElement) {
    const io = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) {
        visible = true;
        io.disconnect();
      }
    }, { rootMargin: "400px" });
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
        <div class="section-heading">
          <h2 class="section-title">{r.name || section.name}</h2>
          <a
            class="view-all"
            href={`/home-section?${new URLSearchParams({ provider, section: String(section.index), row: r.name, name: r.name || section.name })}`}
            aria-label={`View all ${r.name || section.name}`}
          >View all <ChevronRight size={16} strokeWidth={1.8} aria-hidden="true" /></a>
        </div>
        <div class="scroll-x">
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
        </div>
      {/if}
    {/each}
  {:else}
    <h2 class="section-title">{section.name}</h2>
    {#if row.isError}
      <p class="muted">Couldn't load this row: {row.error?.message}</p>
    {:else}
      <LoadingSkeleton horizontal={section.horizontal} />
    {/if}
  {/if}
</section>

<style>
  .section-heading {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    margin: 32px 0 16px;
  }
  .section-heading .section-title {
    margin: 0;
  }
  .view-all {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    min-height: 44px;
    flex-shrink: 0;
    color: var(--accent);
    font-size: 14px;
    font-weight: 600;
  }
  .view-all:hover {
    text-decoration: underline;
    text-underline-offset: 4px;
  }
</style>

