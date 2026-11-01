<script lang="ts">
  import { formatTime, titleHref } from "../api";
  import { player } from "../player.svelte";
  import { sources } from "../sources.svelte";
  import { toasts } from "../toast.svelte";
  import Icon from "./Icon.svelte";

  const s = $derived(player.state);
  const pct = $derived(s.duration > 0 ? (s.position / s.duration) * 100 : 0);
  const busy = $derived(s.status === "resolving");
  // While dragging the seek bar, show the drag position instead of the live one.
  let scrub = $state<number | null>(null);

  async function cmd(name: string, body: Record<string, unknown> = {}) {
    try {
      await player.command(name, body);
    } catch (e) {
      toasts.error(e);
    }
  }

  function openSources() {
    if (!s.provider || !s.url || !s.data) return;
    sources.show({ provider: s.provider, url: s.url, data: s.data, title: s.title ?? "", label: s.episodeLabel ?? null });
  }

  function trackLabel(t: { id: number; title: string | null; lang: string | null; external: boolean }) {
    return [t.lang?.toUpperCase(), t.title].filter(Boolean).join(" · ") || `Track ${t.id}`;
  }
</script>

{#if player.active}
  <div class="bar" class:hidden={player.expanded}>
    <div class="line"><div style="width: {pct}%"></div></div>
    <button class="info" onclick={() => (player.expanded = true)} aria-label="Open remote">
      {#if s.poster}<img src={s.poster} alt="" referrerpolicy="no-referrer" />{/if}
      <div class="text">
        <div class="title">{s.title}</div>
        <div class="sub">
          {#if busy || s.status === "error"}
            <span class:err={s.status === "error"}>{s.message ?? "Loading…"}</span>
          {:else}
            {s.episodeLabel ?? ""}{s.episodeLabel ? " · " : ""}{formatTime(s.position)} / {formatTime(s.duration)}
          {/if}
        </div>
      </div>
    </button>
    {#if busy}<div class="spinner"></div>{/if}
    <button class="icon-btn" onclick={() => cmd("toggle")} aria-label={s.paused ? "Play" : "Pause"} disabled={busy}>
      <Icon name={s.paused ? "play" : "pause"} fill />
    </button>
    {#if s.hasNext}
      <button class="icon-btn hide-sm" onclick={() => cmd("next")} aria-label="Next episode"><Icon name="next" /></button>
    {/if}
  </div>
{/if}

{#if player.expanded}
  <div class="remote" role="dialog" aria-label="Remote">
    <header>
      <button class="icon-btn" onclick={() => (player.expanded = false)} aria-label="Close remote"><Icon name="down" /></button>
      <span class="muted">{s.mpvRunning ? "Playing in mpv" : "mpv is closed"}</span>
      <button class="icon-btn" onclick={openSources} aria-label="Sources"><Icon name="layers" /></button>
    </header>

    <div class="body">
      <div class="cover">
        {#if s.poster}<img src={s.poster} alt="" referrerpolicy="no-referrer" />{:else}<Icon name="tv" size={64} />{/if}
      </div>
      <div class="meta">
        {#if s.provider && s.url}
          <a class="r-title" href={titleHref(s.provider, s.url)} onclick={() => (player.expanded = false)}>{s.title ?? "Nothing playing"}</a>
        {:else}
          <div class="r-title">{s.title ?? "Nothing playing"}</div>
        {/if}
        <div class="muted">{s.episodeLabel ?? ""}</div>
        {#if s.link}<div class="faint">{s.link.source} · {s.link.qualityLabel}{s.link.proxied ? " · proxied" : ""}</div>{/if}
        {#if s.message}<div class="msg" class:err={s.status === "error"}>{s.message}</div>{/if}
      </div>

      <div class="seek">
        <input
          type="range"
          min="0"
          max={Math.max(1, s.duration)}
          step="1"
          value={scrub ?? s.position}
          oninput={(e) => (scrub = +e.currentTarget.value)}
          onchange={(e) => {
            cmd("seek", { seconds: +e.currentTarget.value });
            scrub = null;
          }}
          aria-label="Seek"
          disabled={s.duration <= 0}
        />
        <div class="times"><span>{formatTime(scrub ?? s.position)}</span><span>-{formatTime(s.duration - (scrub ?? s.position))}</span></div>
      </div>

      <div class="controls">
        <button class="icon-btn lg" onclick={() => cmd("prev")} disabled={!s.hasPrev} aria-label="Previous episode"><Icon name="prev" size={26} /></button>
        <button class="icon-btn lg" onclick={() => cmd("seek", { seconds: -10, relative: true })} aria-label="Back 10 seconds"><Icon name="back10" size={28} /></button>
        <button class="play" onclick={() => cmd("toggle")} aria-label={s.paused ? "Play" : "Pause"}>
          {#if busy}<div class="spinner big"></div>{:else}<Icon name={s.paused ? "play" : "pause"} size={34} fill />{/if}
        </button>
        <button class="icon-btn lg" onclick={() => cmd("seek", { seconds: 10, relative: true })} aria-label="Forward 10 seconds"><Icon name="fwd10" size={28} /></button>
        <button class="icon-btn lg" onclick={() => cmd("next")} disabled={!s.hasNext} aria-label="Next episode"><Icon name="next" size={26} /></button>
      </div>

      <div class="tracks">
        <label>
          <Icon name="audio" size={18} />
          <select onchange={(e) => cmd("track", { type: "audio", id: e.currentTarget.value })} disabled={!s.audioTracks.length}>
            {#each s.audioTracks as t (t.id)}<option value={String(t.id)} selected={t.selected}>{trackLabel(t)}</option>{/each}
            {#if !s.audioTracks.length}<option>Audio</option>{/if}
          </select>
        </label>
        <label>
          <Icon name="subtitles" size={18} />
          <select onchange={(e) => cmd("track", { type: "sub", id: e.currentTarget.value })}>
            <option value="no" selected={!s.subTracks.some((t) => t.selected)}>Subtitles off</option>
            {#each s.subTracks as t (t.id)}<option value={String(t.id)} selected={t.selected}>{trackLabel(t)}</option>{/each}
          </select>
        </label>
        <label class="vol">
          <Icon name="volume" size={18} />
          <input type="range" min="0" max="130" value={s.volume} onchange={(e) => cmd("volume", { value: +e.currentTarget.value })} aria-label="Volume" />
        </label>
      </div>

      <div class="actions">
        <button class="btn" onclick={openSources}><Icon name="layers" size={18} /> Sources</button>
        <button class="btn" onclick={() => cmd("stop")}><Icon name="stop" size={18} /> Stop</button>
      </div>
    </div>
  </div>
{/if}

<style>
  .bar {
    position: fixed;
    left: 8px;
    right: 8px;
    bottom: calc(var(--nav-h) + var(--safe-b) + 8px);
    height: var(--bar-h);
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 0 8px 0 0;
    background: rgb(30 33 41 / 0.92);
    backdrop-filter: blur(16px);
    border: 1px solid var(--line);
    border-radius: 14px;
    overflow: hidden;
    z-index: 30;
    box-shadow: 0 8px 30px rgb(0 0 0 / 0.4);
  }
  .bar.hidden {
    visibility: hidden;
  }
  .line {
    position: absolute;
    left: 0;
    right: 0;
    bottom: 0;
    height: 3px;
    background: var(--surface-3);
  }
  .line div {
    height: 100%;
    background: var(--accent);
  }
  .info {
    flex: 1;
    min-width: 0;
    height: 100%;
    display: flex;
    align-items: center;
    gap: 10px;
    padding-left: 8px;
    text-align: left;
  }
  .info img {
    width: 40px;
    height: 52px;
    object-fit: cover;
    border-radius: 6px;
    flex-shrink: 0;
  }
  .text {
    min-width: 0;
  }
  .title {
    font-weight: 650;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .sub {
    font-size: 13px;
    color: var(--muted);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .err {
    color: var(--danger);
  }
  .icon-btn {
    width: 44px;
    height: 44px;
    display: grid;
    place-items: center;
    border-radius: 50%;
    flex-shrink: 0;
  }
  .icon-btn:hover:not(:disabled) {
    background: var(--surface-3);
  }
  .icon-btn:disabled {
    opacity: 0.35;
    cursor: default;
  }
  .icon-btn.lg {
    width: 56px;
    height: 56px;
  }
  .remote {
    position: fixed;
    inset: 0;
    z-index: 50;
    background: linear-gradient(180deg, #1b1e26 0%, var(--bg) 60%);
    display: flex;
    flex-direction: column;
    padding-bottom: var(--safe-b);
    overflow-y: auto;
  }
  header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 8px;
    font-size: 13px;
  }
  .body {
    width: 100%;
    max-width: 520px;
    margin: 0 auto;
    padding: 8px 20px 24px;
    display: flex;
    flex-direction: column;
    gap: 20px;
  }
  .cover {
    width: min(62vw, 260px);
    aspect-ratio: 2 / 3;
    margin: 0 auto;
    border-radius: 16px;
    overflow: hidden;
    background: var(--surface);
    display: grid;
    place-items: center;
    color: var(--faint);
    box-shadow: 0 20px 60px rgb(0 0 0 / 0.5);
  }
  .cover img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
  .meta {
    text-align: center;
    display: flex;
    flex-direction: column;
    gap: 2px;
  }
  .r-title {
    font-size: 21px;
    font-weight: 750;
  }
  .faint {
    color: var(--faint);
    font-size: 13px;
  }
  .msg {
    margin-top: 6px;
    font-size: 14px;
    color: var(--accent);
  }
  .seek input {
    width: 100%;
    padding: 0;
    accent-color: var(--accent);
    background: none;
    border: 0;
  }
  .times {
    display: flex;
    justify-content: space-between;
    font-size: 12.5px;
    color: var(--muted);
    font-variant-numeric: tabular-nums;
  }
  .controls {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }
  .play {
    width: 76px;
    height: 76px;
    border-radius: 50%;
    background: var(--accent);
    color: var(--accent-ink);
    display: grid;
    place-items: center;
  }
  .spinner.big {
    width: 30px;
    height: 30px;
    border-color: rgb(0 0 0 / 0.2);
    border-top-color: var(--accent-ink);
  }
  .tracks {
    display: grid;
    gap: 10px;
  }
  .tracks label {
    display: flex;
    align-items: center;
    gap: 10px;
    color: var(--muted);
  }
  .tracks select,
  .tracks input {
    flex: 1;
  }
  .vol input {
    padding: 0;
    accent-color: var(--accent);
    background: none;
    border: 0;
  }
  .actions {
    display: flex;
    gap: 10px;
    justify-content: center;
  }
  @media (min-width: 900px) {
    .bar {
      left: calc(var(--rail-w) + 16px);
      right: 16px;
      bottom: 16px;
      max-width: 760px;
      margin: 0 auto;
    }
    .hide-sm {
      display: grid;
    }
  }
  @media (max-width: 899px) {
    .hide-sm {
      display: none;
    }
  }
</style>
