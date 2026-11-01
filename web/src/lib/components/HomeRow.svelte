<script lang="ts">
  import { createQuery } from "@tanstack/svelte-query";
  import { api } from "../api";
  import type { HomeSection } from "../types";
  import Poster from "./Poster.svelte";

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
        <h2 class="section-title">{r.name || section.name}</h2>
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
      <div class="scroll-x skeleton">
        {#each Array(8) as _, i (i)}<div class="ph" class:horizontal={section.horizontal}></div>{/each}
      </div>
    {/if}
  {/if}
</section>

<style>
  .skeleton .ph {
    width: 132px;
    aspect-ratio: 2 / 3;
    border-radius: var(--radius);
    background: var(--surface);
    flex-shrink: 0;
    animation: pulse 1.4s ease-in-out infinite;
  }
  .skeleton .ph.horizontal {
    width: 220px;
    aspect-ratio: 16 / 9;
  }
  @keyframes pulse {
    50% {
      opacity: 0.5;
    }
  }
  @media (min-width: 900px) {
    .skeleton .ph {
      width: 156px;
    }
  }
</style>
