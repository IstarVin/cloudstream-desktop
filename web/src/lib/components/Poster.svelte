<script lang="ts">
  import { titleHref } from "../api";
  import { Play } from "@lucide/svelte";

  let {
    provider,
    url,
    name,
    poster,
    meta = "",
    horizontal = false,
    progress = null,
    href = null,
    showProvider = false,
  }: {
    provider: string;
    url: string;
    name: string;
    poster: string | null;
    meta?: string;
    horizontal?: boolean;
    progress?: number | null;
    href?: string | null;
    showProvider?: boolean;
  } = $props();

  let failed = $state(false);
  const initials = $derived(
    name
      .split(/\s+/)
      .slice(0, 2)
      .map((w) => w[0])
      .join("")
      .toUpperCase(),
  );
</script>

<a class="card" class:horizontal href={href ?? titleHref(provider, url)} title={name}>
  <div class="art">
    {#if poster && !failed}
      <img src={poster} alt="" loading="lazy" referrerpolicy="no-referrer" onerror={() => (failed = true)} />
    {:else}
      <span class="initials">{initials}</span>
    {/if}
    <span class="play-hint"><Play size={24} fill="currentColor" strokeWidth={1.8} aria-hidden="true" /></span>
    {#if progress != null}
      <div class="progress"><div style="width: {Math.min(100, progress * 100)}%"></div></div>
    {/if}
  </div>
  <div class="name">{name}</div>
  {#if meta || showProvider}
    <div class="meta">{[showProvider ? provider : "", meta].filter(Boolean).join(" · ")}</div>
  {/if}
</a>

<style>
  .card {
    display: block;
    width: var(--card-w, 132px);
    flex-shrink: 0;
    scroll-snap-align: start;
  }
  .card.horizontal {
    width: var(--card-w-h, 220px);
  }
  .art {
    position: relative;
    aspect-ratio: 2 / 3;
    border-radius: var(--radius);
    overflow: hidden;
    background: var(--surface-2);
    border: 1px solid var(--line);
    display: grid;
    place-items: center;
  }
  .horizontal .art {
    aspect-ratio: 16 / 9;
  }
  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    transition: transform 0.25s;
  }
  .card:hover img {
    transform: scale(1.04);
  }
  .play-hint { position: absolute; inset: 0; display: grid; place-items: center; background: rgb(0 0 0 / 0.35); color: #fff; opacity: 0; transition: opacity 180ms; }
  .card:hover .play-hint, .card:focus-visible .play-hint { opacity: 1; }
  .card:hover .name, .card:focus-visible .name { color: var(--accent); }
  .initials {
    font-size: 28px;
    font-weight: 750;
    color: var(--faint);
  }
  .progress {
    position: absolute;
    left: 0;
    right: 0;
    bottom: 0;
    height: 4px;
    background: rgb(0 0 0 / 0.5);
  }
  .progress div {
    height: 100%;
    background: var(--accent);
  }
  .name {
    margin-top: 8px;
    font-size: 14px;
    font-weight: 600;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }
  .meta {
    font-size: 12.5px;
    color: var(--muted);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  @media (min-width: 900px) {
    .card {
      width: var(--card-w, 164px);
    }
    .card.horizontal {
      width: var(--card-w-h, 272px);
    }
  }
</style>
