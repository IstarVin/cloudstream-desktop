<script lang="ts">
  import { page } from "$app/state";
  import { createQuery, useQueryClient } from "@tanstack/svelte-query";
  import { api, episodeLabel, formatTime } from "#lib/api";
  import EpisodeBrowser from "#lib/components/EpisodeBrowser.svelte";
  import LoadingSkeleton from "#lib/components/LoadingSkeleton.svelte";
  import { ArrowLeft, Bookmark, ChevronDown, Film, Play, RefreshCw, Star, Subtitles, Volume2 } from "@lucide/svelte";
  import Poster from "#lib/components/Poster.svelte";
  import { player } from "#lib/player.svelte";
  import { sources } from "#lib/sources.svelte";
  import { toasts } from "#lib/toast.svelte";
  import { BOOKMARK_LISTS, type Details, type Episode } from "#lib/types";

  const provider = $derived(page.url.searchParams.get("provider") ?? "");
  const url = $derived(page.url.searchParams.get("url") ?? "");
  const client = useQueryClient();

  const details = createQuery(() => ({
    queryKey: ["details", provider, url],
    queryFn: () => api.details(provider, url),
    enabled: !!provider && !!url,
  }));
  // Progress changes while something plays; refresh the episode marks when the player moves on.
  $effect(() => {
    void player.state.data;
    void (player.state.status === "idle");
    client.invalidateQueries({ queryKey: ["details", provider, url] });
  });

  let dubChoice = $state<{ title: string; value: string } | null>(null);
  const titleKey = $derived(JSON.stringify([provider, url]));
  const dub = $derived(dubChoice?.title === titleKey ? dubChoice.value : null);

  const d = $derived(details.data);
  const dubs = $derived([...new Set((d?.episodes ?? []).map((e) => e.dub).filter((x): x is string => !!x))]);
  const activeDub = $derived(dub && dubs.includes(dub) ? dub : (dubs.find((x) => x === "Subbed") ?? dubs[0] ?? null));
  const byDub = $derived((d?.episodes ?? []).filter((e) => !activeDub || e.dub === activeDub));
  const resumeEp = $derived(nextUp(byDub));
  /** The episode to resume: the last one in progress, else the first unwatched after the last watched one. */
  function nextUp(eps: Episode[]): Episode | undefined {
    const inProgress = eps.filter((e) => e.progress && !e.progress.watched && e.progress.position > 30);
    if (inProgress.length) return inProgress[inProgress.length - 1];
    let lastWatched = -1;
    eps.forEach((e, i) => e.progress?.watched && (lastWatched = i));
    return eps[lastWatched + 1] ?? eps[0];
  }

  function play(det: Details, data: string, label: string | null) {
    sources.play({ provider: det.provider, url: det.url, data, title: det.name, label });
  }

  async function bookmark(list: string | null) {
    if (!d) return;
    try {
      await api.setBookmark({ provider: d.provider, url: d.url, title: d.name, poster: d.poster, type: d.type, list });
      client.invalidateQueries({ queryKey: ["details", provider, url] });
      toasts.show(list ? `Added to ${list}` : "Removed from library");
    } catch (e) {
      toasts.error(e);
    }
  }

  async function toggleWatched(e: Episode) {
    if (!d) return;
    await api.setWatched(d.provider, d.url, e.data, !e.progress?.watched).catch(toasts.error);
    client.invalidateQueries({ queryKey: ["details", provider, url] });
  }

  /** Some providers return plots with HTML in them; show them as text with line breaks. */
  const plainText = (html: string) =>
    new DOMParser().parseFromString(html.replace(/<br\s*\/?>/gi, "\n"), "text/html").body.textContent?.trim() ?? "";

  const playing = (data: string) => player.active && player.state.provider === provider && player.state.url === url && player.state.data === data;
</script>

{#if details.isPending}
  <LoadingSkeleton variant="title" />
{:else if details.isError}
  <div class="page">
    <div class="empty">
      <p>Couldn't load this title.</p>
      <p class="muted">{details.error.message}</p>
      <button class="btn" onclick={() => details.refetch()}><RefreshCw size={18} strokeWidth={1.8} aria-hidden="true" /> Retry</button>
    </div>
  </div>
{:else if d}
  <div class="hero" style:--hero={d.background || d.poster ? `url("${d.background || d.poster}")` : "none"}></div>
  <div class="page detail">
    <button class="back" onclick={() => history.back()} aria-label="Go back"><ArrowLeft size={18} strokeWidth={1.8} aria-hidden="true" /></button>
    <div class="head">
      <div class="poster">
        {#if d.poster}<img src={d.poster} alt="" referrerpolicy="no-referrer" />{:else}<Film size={36} strokeWidth={1.8} aria-hidden="true" />{/if}
      </div>
      <div class="info">
        {#if d.logo}
          <img class="logo" src={d.logo} alt={d.name} referrerpolicy="no-referrer" />
        {:else}
          <h1>{d.name}</h1>
        {/if}
        <div class="facts">
          {#if d.year}<span>{d.year}</span>{/if}
          {#if d.score}<span class="score"><Star size={14} fill="currentColor" strokeWidth={1.8} aria-hidden="true" /> {d.score.toFixed(1)}</span>{/if}
          {#if d.contentRating}<span class="badge">{d.contentRating}</span>{/if}
          {#if d.duration}<span>{d.duration} min</span>{/if}
          {#if d.showStatus}<span>{d.showStatus}</span>{/if}
          <span class="muted">{d.provider}</span>
        </div>
        {#if d.tags.length}<div class="tags">{d.tags.slice(0, 6).join(" · ")}</div>{/if}

        <div class="actions">
          {#if d.movieData}
            {@const p = d.movieProgress}
            <button class="btn primary" onclick={() => play(d, d.movieData!, null)}>
              <Play size={18} fill="currentColor" strokeWidth={1.8} aria-hidden="true" />
              {p && !p.watched && p.position > 30 ? `Resume ${formatTime(p.position)}` : d.kind === "live" ? "Watch live" : "Play"}
            </button>
          {:else if resumeEp}
            {@const i = byDub.indexOf(resumeEp)}
            <button class="btn primary" onclick={() => play(d, resumeEp.data, episodeLabel(resumeEp, i))}>
              <Play size={18} fill="currentColor" strokeWidth={1.8} aria-hidden="true" />
              {resumeEp.progress && !resumeEp.progress.watched && resumeEp.progress.position > 30 ? "Resume" : "Play"}
              {episodeLabel({ ...resumeEp, name: null }, i)}
            </button>
          {:else if d.kind === "torrent"}
            <span class="muted">Torrents aren't supported.</span>
          {/if}
          <label class="bm">
            <span class="bm-icon"><Bookmark size={18} fill={d.bookmarked ? "currentColor" : "none"} strokeWidth={1.8} aria-hidden="true" /></span>
            <select value={d.bookmarked ?? ""} onchange={(e) => bookmark(e.currentTarget.value || null)} aria-label="Library list">
              <option value="">{d.bookmarked ? "Remove from library" : "Add to library"}</option>
              {#each BOOKMARK_LISTS as l (l)}<option value={l}>{l[0].toUpperCase() + l.slice(1)}</option>{/each}
            </select>
            <span class="bm-chevron"><ChevronDown size={16} strokeWidth={1.8} aria-hidden="true" /></span>
          </label>
        </div>
      </div>
    </div>

    {#if d.plot}<p class="plot">{plainText(d.plot)}</p>{/if}

    {#if d.episodes.length}
      {#key titleKey}
        <EpisodeBrowser
          episodes={byDub}
          seasons={d.seasons}
          nextEpisode={resumeEp}
          onplay={(episode, index) => play(d, episode.data, episodeLabel(episode, index))}
          onwatched={toggleWatched}
          isPlaying={playing}
        >
          {#snippet audio(resetFilters: () => void)}
            {#if dubs.length > 1}
              <div class="seg" role="group" aria-label="Audio version">
                {#each dubs as x (x)}
                  <button aria-pressed={x === activeDub} class:on={x === activeDub} onclick={() => { resetFilters(); dubChoice = { title: titleKey, value: x }; }}>
                    {#if x === "Dubbed"}<Volume2 size={15} strokeWidth={1.8} aria-hidden="true" />{:else}<Subtitles size={15} strokeWidth={1.8} aria-hidden="true" />{/if}
                    {x === "Dubbed" ? "Dub" : x === "Subbed" ? "Sub" : x}
                  </button>
                {/each}
              </div>
            {/if}
          {/snippet}
        </EpisodeBrowser>
      {/key}
    {/if}

    {#if d.actors.length}
      <h2 class="section-title">Cast</h2>
      <div class="scroll-x cast">
        {#each d.actors.slice(0, 20) as a (a.name + a.role)}
          <div class="actor">
            <div class="face">{#if a.image}<img src={a.image} alt="" loading="lazy" referrerpolicy="no-referrer" />{/if}</div>
            <div class="a-name">{a.name}</div>
            {#if a.role}<div class="a-role">{a.role}</div>{/if}
          </div>
        {/each}
      </div>
    {/if}

    {#if d.recommendations.length}
      <h2 class="section-title">More like this</h2>
      <div class="scroll-x">
        {#each d.recommendations as r (r.url)}
          <Poster provider={r.provider} url={r.url} name={r.name} poster={r.poster} meta={r.year ? String(r.year) : ""} />
        {/each}
      </div>
    {/if}
  </div>
{/if}

<style>
  .hero {
    position: absolute;
    inset: 0 0 auto 0;
    height: 480px;
    background-image: linear-gradient(180deg, rgb(14 15 19 / 0.2), var(--bg)), var(--hero);
    background-size: cover;
    background-position: center 20%;
    z-index: 0;
    pointer-events: none;
    opacity: 0.4;
    mask-image: linear-gradient(180deg, #000 40%, transparent);
  }
  .detail {
    position: relative;
  }
  .back {
    width: 40px;
    height: 40px;
    display: grid;
    place-items: center;
    border-radius: 50%;
    background: rgb(0 0 0 / 0.35);
    margin-bottom: 24px;
  }
  .head {
    display: flex;
    gap: 28px;
    align-items: center;
  }
  .poster {
    display: grid;
    place-items: center;
    color: var(--muted);
    width: 112px;
    aspect-ratio: 2 / 3;
    flex-shrink: 0;
    border-radius: var(--radius);
    overflow: hidden;
    background: var(--surface);
    box-shadow: 0 12px 40px rgb(0 0 0 / 0.5);
  }
  .poster img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
  .info {
    min-width: 0;
    flex: 1;
  }
  h1 {
    font-size: clamp(24px, 3.5vw, 42px);
    text-wrap: balance;
    font-weight: 750;
  }
  .logo {
    max-width: 100%;
    max-height: 72px;
    object-fit: contain;
    object-position: left;
  }
  .facts {
    display: flex;
    flex-wrap: wrap;
    gap: 4px 12px;
    margin-top: 8px;
    font-size: 13.5px;
  }
  .score {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    color: #ffcf5c;
  }
  .badge {
    border: 1px solid var(--line);
    border-radius: 4px;
    padding: 0 5px;
    font-size: 12px;
  }
  .tags {
    margin-top: 4px;
    font-size: 13px;
    color: var(--muted);
  }
  .actions {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-top: 14px;
  }
  .bm {
    position: relative;
    display: inline-block;
    max-width: 100%;
    color: var(--muted);
  }
  .bm-icon, .bm-chevron {
    position: absolute;
    top: 50%;
    display: flex;
    transform: translateY(-50%);
    pointer-events: none;
  }
  .bm-icon { left: 14px; }
  .bm-chevron { right: 14px; }
  .bm select {
    appearance: none;
    max-width: 100%;
    min-height: 44px;
    padding: 9px 40px 9px 42px;
    border-radius: 999px;
    cursor: pointer;
    transition: background 0.15s;
  }
  .bm select:hover { background: var(--surface-3); }
  .plot {
    margin: 28px 0 0;
    color: var(--muted);
    max-width: 75ch;
    white-space: pre-line;
    line-height: 1.75;
  }
  .seg {
    display: inline-flex;
    align-self: flex-start;
    background: var(--surface-2);
    border-radius: 999px;
    padding: 3px;
  }
  .seg button {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    min-height: 38px;
    padding: 6px 14px;
    border-radius: 999px;
    font-weight: 600;
    font-size: 14px;
    color: var(--muted);
  }
  .seg button.on {
    background: var(--surface-3);
    color: var(--text);
  }
  .cast {
    gap: 14px;
  }
  .actor {
    width: 84px;
    flex-shrink: 0;
    text-align: center;
  }
  .face {
    width: 72px;
    height: 72px;
    margin: 0 auto 6px;
    border-radius: 50%;
    overflow: hidden;
    background: var(--surface-2);
  }
  .face img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
  .a-name {
    font-size: 12.5px;
    font-weight: 600;
  }
  .a-role {
    font-size: 11.5px;
    color: var(--muted);
  }
  @media (max-width: 599px) {
    .head { align-items: flex-start; gap: 16px; }
    .poster { width: 86px; }
    .facts { gap: 5px 10px; font-size: 12px; }
    .actions { margin-top: 16px; }
    .actions .btn { white-space: normal; text-align: left; padding-block: 10px; }
    .bm select { border-radius: var(--radius-sm); }
    .seg button { padding-inline: 10px; gap: 5px; font-size: 13px; }
  }
  @media (min-width: 900px) {
    .poster {
      width: 208px;
    }
    h1 {
      font-size: clamp(32px, 3.5vw, 46px);
    }
    .hero {
      left: var(--rail-w);
      height: 520px;
    }
  }
</style>
