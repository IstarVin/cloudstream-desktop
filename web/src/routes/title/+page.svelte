<script lang="ts">
  import { page } from "$app/state";
  import { createQuery, useQueryClient } from "@tanstack/svelte-query";
  import { api, episodeLabel, formatTime } from "#lib/api";
  import Icon from "#lib/components/Icon.svelte";
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

  let dub = $state<string | null>(null);
  let season = $state<number | null>(null);

  const d = $derived(details.data);
  const dubs = $derived([...new Set((d?.episodes ?? []).map((e) => e.dub).filter((x): x is string => !!x))]);
  const activeDub = $derived(dub && dubs.includes(dub) ? dub : (dubs.find((x) => x === "Subbed") ?? dubs[0] ?? null));
  const byDub = $derived((d?.episodes ?? []).filter((e) => !activeDub || e.dub === activeDub));
  const seasons = $derived([...new Set(byDub.map((e) => e.season ?? 0))].sort((a, b) => a - b));
  const resumeEp = $derived(nextUp(byDub));
  const activeSeason = $derived(season != null && seasons.includes(season) ? season : (resumeEp?.season ?? seasons[0] ?? 0));
  const shown = $derived(byDub.filter((e) => (e.season ?? 0) === activeSeason));

  function seasonName(n: number) {
    const s = d?.seasons.find((x) => x.season === n);
    return s?.name ?? (n === 0 ? "Episodes" : `Season ${s?.displaySeason ?? n}`);
  }

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

  const playing = (data: string) => player.state.provider === provider && player.state.data === data;
</script>

{#if details.isPending}
  <div class="empty"><div class="spinner" style="margin: 0 auto"></div></div>
{:else if details.isError}
  <div class="page">
    <div class="empty">
      <p>Couldn't load this title.</p>
      <p class="muted">{details.error.message}</p>
      <button class="btn" onclick={() => details.refetch()}><Icon name="refresh" size={18} /> Retry</button>
    </div>
  </div>
{:else if d}
  <div class="hero" style:--hero={d.background || d.poster ? `url("${d.background || d.poster}")` : "none"}></div>
  <div class="page detail">
    <button class="back" onclick={() => history.back()} aria-label="Back"><Icon name="back" /></button>
    <div class="head">
      <div class="poster">
        {#if d.poster}<img src={d.poster} alt="" referrerpolicy="no-referrer" />{/if}
      </div>
      <div class="info">
        {#if d.logo}
          <img class="logo" src={d.logo} alt={d.name} referrerpolicy="no-referrer" />
        {:else}
          <h1>{d.name}</h1>
        {/if}
        <div class="facts">
          {#if d.year}<span>{d.year}</span>{/if}
          {#if d.score}<span class="score"><Icon name="star" size={14} fill /> {d.score.toFixed(1)}</span>{/if}
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
              <Icon name="play" size={18} fill />
              {p && !p.watched && p.position > 30 ? `Resume ${formatTime(p.position)}` : d.kind === "live" ? "Watch live" : "Play"}
            </button>
          {:else if resumeEp}
            {@const i = byDub.indexOf(resumeEp)}
            <button class="btn primary" onclick={() => play(d, resumeEp.data, episodeLabel(resumeEp, i))}>
              <Icon name="play" size={18} fill />
              {resumeEp.progress && !resumeEp.progress.watched && resumeEp.progress.position > 30 ? "Resume" : "Play"}
              {episodeLabel({ ...resumeEp, name: null }, i)}
            </button>
          {:else if d.kind === "torrent"}
            <span class="muted">Torrents aren't supported.</span>
          {/if}
          <label class="bm">
            <Icon name="bookmark" size={18} fill={!!d.bookmarked} />
            <select value={d.bookmarked ?? ""} onchange={(e) => bookmark(e.currentTarget.value || null)} aria-label="Library list">
              <option value="">Add to library</option>
              {#each BOOKMARK_LISTS as l (l)}<option value={l}>{l[0].toUpperCase() + l.slice(1)}</option>{/each}
            </select>
          </label>
        </div>
      </div>
    </div>

    {#if d.plot}<p class="plot">{plainText(d.plot)}</p>{/if}

    {#if d.episodes.length}
      <div class="ep-head">
        {#if dubs.length > 1}
          <div class="seg" role="tablist" aria-label="Audio">
            {#each dubs as x (x)}
              <button role="tab" aria-selected={x === activeDub} class:on={x === activeDub} onclick={() => (dub = x)}>{x === "Dubbed" ? "Dub" : x === "Subbed" ? "Sub" : x}</button>
            {/each}
          </div>
        {/if}
        {#if seasons.length > 1}
          <div class="scroll-x seasons">
            {#each seasons as n (n)}
              <button class="chip" class:on={n === activeSeason} onclick={() => (season = n)}>{seasonName(n)}</button>
            {/each}
          </div>
        {/if}
      </div>

      <ol class="episodes">
        {#each shown as e (e.data)}
          {@const i = byDub.indexOf(e)}
          {@const p = e.progress}
          <li class:watched={p?.watched} class:now={playing(e.data)}>
            <button class="ep" onclick={() => play(d, e.data, episodeLabel(e, i))}>
              <div class="thumb">
                {#if e.poster}<img src={e.poster} alt="" loading="lazy" referrerpolicy="no-referrer" />{/if}
                <span class="num">{e.episode ?? i + 1}</span>
                {#if p && !p.watched && p.duration > 0}<div class="bar"><div style="width: {(p.position / p.duration) * 100}%"></div></div>{/if}
              </div>
              <div class="ep-text">
                <div class="ep-name">{e.name ?? `Episode ${e.episode ?? i + 1}`}</div>
                <div class="ep-meta">
                  {#if playing(e.data)}<span class="accent">Playing</span>{/if}
                  {#if p && !p.watched && p.position > 30}<span>{formatTime(p.position)} watched</span>{/if}
                  {#if e.runtime}<span>{e.runtime} min</span>{/if}
                  {#if e.date}<span>{new Date(e.date).toLocaleDateString()}</span>{/if}
                </div>
                {#if e.description}<div class="ep-desc">{e.description}</div>{/if}
              </div>
            </button>
            <button class="mark" onclick={() => toggleWatched(e)} aria-label={p?.watched ? "Mark unwatched" : "Mark watched"} aria-pressed={!!p?.watched}>
              <Icon name="check" size={18} />
            </button>
          </li>
        {/each}
      </ol>
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
    height: 420px;
    background-image: linear-gradient(180deg, rgb(14 15 19 / 0.2), var(--bg)), var(--hero);
    background-size: cover;
    background-position: center 20%;
    z-index: -1;
    opacity: 0.55;
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
    margin-bottom: 12px;
  }
  .head {
    display: flex;
    gap: 16px;
    align-items: flex-end;
  }
  .poster {
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
    font-size: 24px;
    font-weight: 800;
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
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding-left: 12px;
    border-radius: 999px;
    background: var(--surface-2);
    border: 1px solid var(--line);
    color: var(--muted);
  }
  .bm select {
    border: 0;
    background: none;
    padding: 9px 12px 9px 0;
  }
  .plot {
    margin: 18px 0 0;
    color: #cfd2da;
    max-width: 75ch;
    white-space: pre-line;
  }
  .ep-head {
    display: flex;
    flex-direction: column;
    gap: 10px;
    margin: 26px 0 8px;
  }
  .seg {
    display: inline-flex;
    align-self: flex-start;
    background: var(--surface-2);
    border-radius: 999px;
    padding: 3px;
  }
  .seg button {
    padding: 6px 16px;
    border-radius: 999px;
    font-weight: 600;
    font-size: 14px;
    color: var(--muted);
  }
  .seg button.on {
    background: var(--surface-3);
    color: var(--text);
  }
  .seasons {
    margin: 0 -16px;
    padding: 0 16px;
  }
  .episodes {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-direction: column;
    gap: 4px;
  }
  .episodes li {
    display: flex;
    align-items: center;
    border-radius: var(--radius);
  }
  .episodes li:hover {
    background: var(--surface);
  }
  .episodes li.now {
    background: var(--accent-soft);
  }
  .ep {
    flex: 1;
    min-width: 0;
    display: flex;
    gap: 12px;
    padding: 8px;
    text-align: left;
    align-items: flex-start;
  }
  .thumb {
    position: relative;
    width: 128px;
    aspect-ratio: 16 / 9;
    border-radius: var(--radius-sm);
    overflow: hidden;
    background: var(--surface-2);
    flex-shrink: 0;
    display: grid;
    place-items: center;
  }
  .thumb img {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
  .num {
    position: relative;
    font-weight: 800;
    font-size: 18px;
    color: var(--faint);
  }
  .thumb img + .num {
    position: absolute;
    left: 6px;
    bottom: 4px;
    font-size: 13px;
    color: #fff;
    text-shadow: 0 1px 3px #000;
  }
  .bar {
    position: absolute;
    left: 0;
    right: 0;
    bottom: 0;
    height: 3px;
    background: rgb(0 0 0 / 0.5);
  }
  .bar div {
    height: 100%;
    background: var(--accent);
  }
  .watched .thumb {
    opacity: 0.55;
  }
  .ep-text {
    min-width: 0;
  }
  .ep-name {
    font-weight: 600;
  }
  .ep-meta {
    display: flex;
    flex-wrap: wrap;
    gap: 0 10px;
    font-size: 12.5px;
    color: var(--muted);
  }
  .accent {
    color: var(--accent);
    font-weight: 600;
  }
  .ep-desc {
    margin-top: 2px;
    font-size: 13px;
    color: var(--faint);
    display: -webkit-box;
    -webkit-line-clamp: 2;
    line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }
  .mark {
    width: 44px;
    height: 44px;
    display: grid;
    place-items: center;
    border-radius: 50%;
    color: var(--faint);
    flex-shrink: 0;
  }
  .mark[aria-pressed="true"] {
    color: var(--good);
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
  @media (min-width: 900px) {
    .poster {
      width: 200px;
    }
    h1 {
      font-size: 34px;
    }
    .hero {
      left: var(--rail-w);
      height: 520px;
    }
    .thumb {
      width: 176px;
    }
  }
</style>
