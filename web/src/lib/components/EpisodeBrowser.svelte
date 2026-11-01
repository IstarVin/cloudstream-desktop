<script lang="ts">
  import {
    ArrowRight,
    Check,
    ChevronLeft,
    ChevronRight,
    CircleCheck,
    Clock3,
    ListVideo,
    Play,
    Search,
    Volume1,
  } from "@lucide/svelte";
  import { tick, type Snippet } from "svelte";
  import { formatTime } from "../api";
  import type { Details, Episode } from "../types";

  let {
    episodes,
    seasons,
    nextEpisode,
    onplay,
    onwatched,
    isPlaying,
    audio,
  }: {
    episodes: Episode[];
    seasons: Details["seasons"];
    nextEpisode?: Episode;
    onplay: (episode: Episode, index: number) => void;
    onwatched: (episode: Episode) => void;
    isPlaying: (data: string) => boolean;
    audio?: Snippet<[resetFilters: () => void]>;
  } = $props();

  const PAGE_SIZE = 20;
  const uid = $props.id();
  let selectedSeason = $state<number | null>(null);
  let search = $state("");
  let navigation = $state<{ scope: string; page: number } | null>(null);
  let section: HTMLElement;

  const seasonNumbers = $derived(
    [...new Set(episodes.map((e) => e.season ?? 0))].sort((a, b) => a - b),
  );
  const lastPlayed = $derived(
    episodes.reduce<Episode | undefined>((latest, episode) => {
      if (!episode.progress || episode.progress.position <= 0) return latest;
      return !latest || episode.progress.updated > (latest.progress?.updated ?? 0)
        ? episode
        : latest;
    }, undefined),
  );
  const activeSeason = $derived(
    selectedSeason != null && seasonNumbers.includes(selectedSeason)
      ? selectedSeason
      : lastPlayed
        ? (lastPlayed.season ?? 0)
        : (nextEpisode?.season ?? seasonNumbers[0] ?? 0),
  );
  // Carry the original index through filters so unnumbered episodes keep their labels.
  const inSeason = $derived(
    episodes
      .map((episode, index) => ({ episode, index }))
      .filter(({ episode }) => (episode.season ?? 0) === activeSeason),
  );
  const hasArtwork = $derived(inSeason.some(({ episode }) => !!episode.poster));
  const query = $derived(search.trim().toLocaleLowerCase());
  const filtered = $derived(
    inSeason.filter(
      ({ episode, index }) =>
        !query ||
        `${episode.episode ?? index + 1} ${episode.name ?? ""}`.toLocaleLowerCase().includes(query),
    ),
  );
  const watched = $derived(inSeason.filter(({ episode }) => episode.progress?.watched).length);
  const pageCount = $derived(Math.max(1, Math.ceil(filtered.length / PAGE_SIZE)));
  const lastPlayedIndex = $derived(
    filtered.findIndex(({ episode }) => episode.data === lastPlayed?.data),
  );
  const scope = $derived(JSON.stringify([activeSeason, query]));
  const currentPage = $derived(
    Math.min(
      pageCount,
      navigation?.scope === scope
        ? navigation.page
        : !query && lastPlayedIndex >= 0
          ? Math.floor(lastPlayedIndex / PAGE_SIZE) + 1
          : 1,
    ),
  );
  const offset = $derived((currentPage - 1) * PAGE_SIZE);
  const visible = $derived(filtered.slice(offset, offset + PAGE_SIZE));
  const ranges = $derived(
    Array.from({ length: pageCount }, (_, i) => ({
      page: i + 1,
      start: i * PAGE_SIZE + 1,
      end: Math.min((i + 1) * PAGE_SIZE, filtered.length),
    })),
  );

  function seasonName(n: number) {
    const season = seasons.find((s) => s.season === n);
    return season?.name ?? (n === 0 ? "Episodes" : `Season ${season?.displaySeason ?? n}`);
  }

  async function goToPage(value: number, fromBottom = false) {
    navigation = { scope, page: Math.max(1, Math.min(pageCount, value)) };
    if (fromBottom) {
      await tick();
      section.scrollIntoView({ block: "start" });
      section.focus({ preventScroll: true });
    }
  }

  async function findNext() {
    if (!nextEpisode) return;
    selectedSeason = nextEpisode.season ?? 0;
    search = "";
    const index = episodes
      .filter((e) => (e.season ?? 0) === selectedSeason)
      .findIndex((e) => e.data === nextEpisode.data);
    navigation = {
      scope: JSON.stringify([selectedSeason, ""]),
      page: Math.floor(Math.max(0, index) / PAGE_SIZE) + 1,
    };
    await tick();
    const target = section.querySelector<HTMLButtonElement>("[data-next-episode]");
    target?.scrollIntoView({ block: "center" });
    target?.focus({ preventScroll: true });
  }

  function resetFilters() {
    selectedSeason = null;
    search = "";
    navigation = null;
  }
</script>

{#snippet pagination(bottom = false)}
  <nav class="pagination" aria-label={bottom ? "Episode pages, bottom" : "Episode pages"}>
    <button
      class="page-button"
      aria-label="Previous episode page"
      disabled={currentPage === 1}
      onclick={() => goToPage(currentPage - 1, bottom)}
    >
      <ChevronLeft size={18} strokeWidth={1.8} aria-hidden="true" />
    </button>
    <label class="range-picker">
      <span class="sr-only">Episode range</span>
      <select
        aria-label="Episode range"
        value={currentPage}
        onchange={(e) => goToPage(Number(e.currentTarget.value), bottom)}
      >
        {#each ranges as range (range.page)}<option value={range.page}
            >{range.start}–{range.end}</option
          >{/each}
      </select>
    </label>
    <span class="page-total">of {filtered.length}</span>
    <button
      class="page-button"
      aria-label="Next episode page"
      disabled={currentPage === pageCount}
      onclick={() => goToPage(currentPage + 1, bottom)}
    >
      <ChevronRight size={18} strokeWidth={1.8} aria-hidden="true" />
    </button>
  </nav>
{/snippet}

<section class="episode-browser" aria-labelledby="{uid}-heading" bind:this={section} tabindex="-1">
  <div class="episode-heading">
    <div>
      <h2 id="{uid}-heading">
        <ListVideo size={22} strokeWidth={1.8} aria-hidden="true" /> Episodes
        <span class="count">{episodes.length}</span>
      </h2>
      <p>
        {watched} of {inSeason.length} watched{seasonNumbers.length > 1
          ? ` in ${seasonName(activeSeason)}`
          : ""}
      </p>
    </div>
    {#if audio}{@render audio(resetFilters)}{/if}
  </div>

  <div class="toolbar">
    {#if seasonNumbers.length > 1}
      <label class="season-picker">
        <span class="sr-only">Season</span>
        <select
          aria-label="Season"
          value={activeSeason}
          onchange={(e) => {
            selectedSeason = Number(e.currentTarget.value);
            navigation = {
              scope: JSON.stringify([selectedSeason, query]),
              page: 1,
            };
          }}
        >
          {#each seasonNumbers as n (n)}<option value={n}>{seasonName(n)}</option>{/each}
        </select>
      </label>
    {/if}
    <div class="episode-search">
      <Search size={17} strokeWidth={1.8} aria-hidden="true" />
      <input
        type="search"
        aria-label={seasonNumbers.length > 1
          ? `Find an episode in ${seasonName(activeSeason)}`
          : "Find an episode"}
        placeholder={seasonNumbers.length > 1 ? "Search this season" : "Find by name or number"}
        enterkeyhint="search"
        bind:value={search}
      />
    </div>
    {#if nextEpisode}
      <button class="find-next" onclick={findNext}
        ><ArrowRight size={16} strokeWidth={1.8} aria-hidden="true" /> Jump to next episode</button
      >
    {/if}
  </div>

  <div class="list-heading">
    <span aria-live="polite"
      >{query
        ? `${filtered.length} matching episode${filtered.length === 1 ? "" : "s"}`
        : seasonName(activeSeason)}</span
    >
    {#if filtered.length > PAGE_SIZE}{@render pagination()}{/if}
  </div>

  {#if visible.length}
    <ol class="episodes" start={offset + 1}>
      {#each visible as { episode: e, index } (e.data)}
        {@const p = e.progress}
        {@const playing = isPlaying(e.data)}
        {@const next = e.data === nextEpisode?.data}
        <li class={[{ watched: p?.watched, now: playing, next: next && !playing }]}>
          <button
            class="episode"
            onclick={() => onplay(e, index)}
            aria-label="Play episode {e.episode ?? index + 1}{e.name ? `: ${e.name}` : ''}"
            aria-current={playing ? "true" : undefined}
            data-next-episode={next ? "" : undefined}
          >
            <span class={["thumb", { compact: !hasArtwork }]} aria-hidden="true">
              {#if e.poster}<img
                  src={e.poster}
                  alt=""
                  loading="lazy"
                  referrerpolicy="no-referrer"
                  onerror={(event) => event.currentTarget.setAttribute("hidden", "")}
                  onload={(event) => event.currentTarget.removeAttribute("hidden")}
                />{/if}
              <span class="number">{e.episode ?? index + 1}</span>
              <span class="play-icon"
                ><Play size={22} fill="currentColor" strokeWidth={1.8} aria-hidden="true" /></span
              >
              {#if p && !p.watched && p.duration > 0}<span class="progress"
                  ><span
                    style:width={`${Math.min(100, Math.max(0, (p.position / p.duration) * 100))}%`}
                  ></span></span
                >{/if}
            </span>
            <span class="episode-text">
              <span class="episode-name">{e.name ?? `Episode ${e.episode ?? index + 1}`}</span>
              <span class="episode-meta">
                {#if playing}<span class="playing"
                    ><Volume1 size={13} strokeWidth={1.8} aria-hidden="true" /> Playing</span
                  >
                {:else if next}<span class="up-next">Up next</span>{/if}
                {#if p?.watched}<span class="watched-label"
                    ><CircleCheck size={13} strokeWidth={1.8} aria-hidden="true" /> Watched</span
                  >
                {:else if p && p.position > 30}<span
                    >{p.duration > p.position
                      ? `${formatTime(p.duration - p.position)} left`
                      : `${formatTime(p.position)} watched`}</span
                  >{/if}
                {#if e.runtime}<span
                    ><Clock3 size={13} strokeWidth={1.8} aria-hidden="true" />
                    {e.runtime} min</span
                  >{/if}
                {#if e.date}<span>{new Date(e.date).toLocaleDateString()}</span>{/if}
              </span>
              {#if e.description}<span class="episode-description">{e.description}</span>{/if}
            </span>
          </button>
          <button
            class="mark"
            onclick={() => onwatched(e)}
            aria-label="{p?.watched ? 'Mark unwatched' : 'Mark watched'}: {e.name ??
              `Episode ${e.episode ?? index + 1}`}"
            title={p?.watched ? "Mark unwatched" : "Mark watched"}
            aria-pressed={!!p?.watched}
          >
            {#if p?.watched}<CircleCheck
                size={19}
                strokeWidth={1.8}
                aria-hidden="true"
              />{:else}<Check size={19} strokeWidth={1.8} aria-hidden="true" />{/if}
          </button>
        </li>
      {/each}
    </ol>
  {:else}
    <div class="empty-results">
      <Search size={28} strokeWidth={1.8} aria-hidden="true" />
      <p>No episodes match “{search.trim()}”.</p>
      <button class="btn" onclick={() => (search = "")}>Clear search</button>
    </div>
  {/if}

  {#if filtered.length > PAGE_SIZE}
    <div class="list-footer">
      <span>Page {currentPage} of {pageCount}</span>
      {@render pagination(true)}
    </div>
  {/if}
</section>

<style>
  .episode-browser {
    margin-top: 40px;
    scroll-margin-top: 24px;
  }
  .episode-browser:focus {
    outline: none;
  }
  .episode-heading,
  .toolbar,
  .list-heading,
  .list-footer,
  .pagination {
    display: flex;
    align-items: center;
  }
  .episode-heading {
    justify-content: space-between;
    gap: 16px;
    flex-wrap: wrap;
  }
  h2 {
    display: flex;
    align-items: center;
    gap: 10px;
    font-size: 22px;
  }
  h2 :global(svg) {
    color: var(--muted);
  }
  .count {
    font-size: 13px;
    font-weight: 500;
    color: var(--muted);
    background: var(--surface-2);
    padding: 4px 8px;
    border-radius: 6px;
    font-variant-numeric: tabular-nums;
  }
  .episode-heading p {
    margin: 7px 0 0;
    color: var(--muted);
    font-size: 13px;
  }
  .toolbar {
    flex-wrap: wrap;
    gap: 12px;
    margin-top: 20px;
  }
  .season-picker select {
    max-width: 100%;
    min-height: 44px;
    font-weight: 600;
  }
  .episode-search {
    display: flex;
    align-items: center;
    gap: 9px;
    padding: 0 12px;
    border: 1px solid var(--line);
    background: var(--surface);
    border-radius: var(--radius-sm);
    color: var(--muted);
    flex: 1;
    min-width: 190px;
    max-width: 360px;
  }
  .episode-search:focus-within {
    border-color: var(--accent);
    outline: 2px solid var(--accent);
    outline-offset: 3px;
  }
  .episode-search input {
    width: 100%;
    min-height: 42px;
    padding: 0;
    border: 0;
    background: none;
    font-size: 13px;
  }
  .episode-search input:focus-visible {
    outline: none;
  }
  .find-next {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    color: var(--accent);
    font-size: 13px;
    min-height: 44px;
    padding: 0 4px;
    margin-left: auto;
  }
  .find-next:hover {
    text-decoration: underline;
    text-underline-offset: 4px;
  }
  .list-heading {
    justify-content: space-between;
    gap: 12px;
    min-height: 66px;
    color: var(--muted);
    font-size: 13px;
  }
  .pagination {
    gap: 8px;
    flex-shrink: 0;
    font-variant-numeric: tabular-nums;
  }
  .page-button {
    display: grid;
    place-items: center;
    width: 44px;
    height: 44px;
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
  }
  .page-button:hover:not(:disabled) {
    color: var(--accent);
    background: var(--surface-2);
  }
  .page-button:disabled {
    opacity: 0.35;
    cursor: default;
  }
  .range-picker select {
    min-height: 44px;
    padding: 6px 8px;
    font-size: 13px;
    font-variant-numeric: tabular-nums;
    background: var(--surface);
  }
  .page-total {
    font-size: 12px;
    color: var(--muted);
  }
  .episodes {
    list-style: none;
    margin: 0;
    padding: 0;
    border-top: 1px solid var(--line);
  }
  li {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 12px;
    border-bottom: 1px solid var(--line);
    transition: background 150ms;
  }
  li:hover {
    background: var(--surface);
  }
  li.now {
    background: var(--accent-soft);
  }
  li.next {
    background: var(--surface);
  }
  .episode {
    flex: 1;
    min-width: 0;
    display: flex;
    align-items: center;
    gap: 18px;
    text-align: left;
    min-height: 52px;
    border-radius: var(--radius-sm);
    scroll-margin-block: 24px;
  }
  .thumb {
    position: relative;
    width: 148px;
    aspect-ratio: 16 / 9;
    flex-shrink: 0;
    display: grid;
    place-items: center;
    overflow: hidden;
    border-radius: var(--radius-sm);
    background: var(--surface-2);
  }
  .thumb img {
    position: absolute;
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
  .thumb.compact {
    width: 48px;
    aspect-ratio: 1;
    background: transparent;
  }
  .number {
    position: relative;
    font-size: 23px;
    font-weight: 650;
    color: var(--muted);
    font-variant-numeric: tabular-nums;
  }
  .thumb:has(img:not([hidden])) .number {
    position: absolute;
    bottom: 6px;
    left: 7px;
    padding: 1px 6px;
    border-radius: 4px;
    background: rgb(0 0 0 / 0.75);
    font-size: 12px;
    color: var(--on-image);
  }
  .play-icon {
    position: absolute;
    inset: 0;
    display: grid;
    place-items: center;
    background: rgb(0 0 0 / 0.4);
    color: var(--on-image);
    opacity: 0;
    transition: opacity 150ms;
  }
  .episode:hover .play-icon,
  .episode:focus-visible .play-icon {
    opacity: 1;
  }
  .progress {
    position: absolute;
    left: 0;
    right: 0;
    bottom: 0;
    height: 3px;
    background: rgb(0 0 0 / 0.5);
  }
  .progress > span {
    display: block;
    height: 100%;
    background: var(--accent);
  }
  .episode-text {
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 5px;
    padding-block: 4px;
  }
  .episode-name {
    font-size: 14px;
    font-weight: 600;
    overflow-wrap: anywhere;
  }
  .episode-meta {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 5px 14px;
    font-size: 12px;
    color: var(--muted);
  }
  .episode-meta > span {
    display: inline-flex;
    align-items: center;
    gap: 4px;
  }
  .playing {
    color: var(--accent);
  }
  .up-next {
    color: var(--accent);
    font-weight: 600;
  }
  .next .number,
  .now .number {
    color: var(--accent);
  }
  .watched-label {
    color: var(--good);
  }
  .episode-description {
    font-size: 13px;
    color: var(--muted);
    display: -webkit-box;
    -webkit-line-clamp: 2;
    line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }
  .watched .thumb {
    opacity: 0.65;
  }
  .mark {
    display: grid;
    place-items: center;
    width: 44px;
    height: 44px;
    border-radius: var(--radius-sm);
    color: var(--muted);
    flex-shrink: 0;
  }
  .mark:hover {
    background: var(--surface-2);
    color: var(--text);
  }
  .mark[aria-pressed="true"] {
    color: var(--good);
  }
  .list-footer {
    justify-content: space-between;
    gap: 12px;
    margin-top: 20px;
    color: var(--muted);
    font-size: 12px;
    font-variant-numeric: tabular-nums;
  }
  .empty-results {
    text-align: center;
    padding: 40px 16px;
    color: var(--muted);
    border-block: 1px solid var(--line);
    overflow-wrap: anywhere;
  }
  .empty-results :global(svg) {
    margin: 0 auto;
  }
  @media (max-width: 599px) {
    .episode-browser {
      margin-top: 30px;
    }
    h2 {
      font-size: 20px;
    }
    .toolbar {
      gap: 8px;
    }
    .season-picker {
      flex: 0 1 132px;
      min-width: 0;
    }
    .season-picker select {
      width: 100%;
      font-size: 13px;
      padding-inline: 8px;
    }
    .episode-search {
      min-width: 120px;
      max-width: none;
    }
    .episode-search input {
      font-size: 16px;
    }
    .find-next {
      margin-left: 0;
    }
    .episode {
      gap: 10px;
    }
    .thumb {
      width: 96px;
    }
    .episode-description {
      display: none;
    }
    .episode-meta {
      gap: 3px 8px;
    }
    li {
      padding: 12px 8px;
      gap: 0;
    }
    .mark {
      width: 44px;
    }
    .thumb.compact {
      width: 32px;
    }
    .thumb.compact .number {
      font-size: 18px;
    }
    .list-heading {
      flex-wrap: wrap;
      padding-block: 12px;
    }
    .list-footer {
      flex-wrap: wrap;
    }
  }
</style>
