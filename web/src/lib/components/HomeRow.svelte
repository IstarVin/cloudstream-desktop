<script lang="ts">
  import { createQuery } from "@tanstack/svelte-query";
  import { ChevronRight } from "@lucide/svelte";
  import { api } from "../api";
  import type { HomeSection } from "../types";
  import Poster from "./Poster.svelte";
  import LoadingSkeleton from "./LoadingSkeleton.svelte";

  let { provider, section }: { provider: string; section: HomeSection } = $props();

  // Rows load when they scroll near the viewport: one getMainPage request per row, like the app.
  let visible = $state(false);
  let el: HTMLElement;
  $effect(() => {
    const io = new IntersectionObserver((e) => e.some((x) => x.isIntersecting) && (visible = true), {
      rootMargin: "400px",
    });
    io.observe(el);
    return () => io.disconnect();
  });

  const row = createQuery(() => ({
    queryKey: ["home", provider, section.index],
    queryFn: () => api.homeRow(provider, section.index),
    enabled: visible,
    staleTime: 5 * 60_000,
  }));
</script>

<section bind:this={el}>
  {#if row.data}
    {#each row.data.rows as r (r.name)}
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
          {#each r.items as item (item.url)}
            <Poster
              provider={item.provider}
              url={item.url}
              name={item.name}
              poster={item.poster}
              horizontal={r.horizontal}
              meta={[item.year, item.quality].filter(Boolean).join(" · ")}
            />
          {/each}
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

