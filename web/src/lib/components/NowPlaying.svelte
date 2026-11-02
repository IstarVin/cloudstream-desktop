<script lang="ts">
  import {
    Captions,
    ChevronDown,
    FastForward,
    Layers,
    MonitorPlay,
    Pause,
    Play,
    RotateCcw,
    RotateCw,
    SkipBack,
    SkipForward,
    Square,
    Volume1,
    Volume2,
  } from "@lucide/svelte";
  import { formatTime, titleHref } from "../api";
  import { modal } from "../dialog";
  import { player } from "../player.svelte";
  import { sources } from "../sources.svelte";
  import { toasts } from "../toast.svelte";

  const s = $derived(player.state);
  const info = $derived(player.info);
  const isMpv = $derived(info?.kind === "mpv");
  const online = $derived(isMpv ? s.mpvRunning : !!info);
  const statusText = $derived(
    isMpv
      ? s.mpvRunning
        ? "Playing in mpv"
        : "mpv is closed"
      : info
        ? `Playing on ${info.name}`
        : "Device disconnected",
  );
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
    sources.show({
      provider: s.provider,
      url: s.url,
      data: s.data,
      title: s.title ?? "",
      label: s.episodeLabel ?? null,
    });
  }

  const nextText = $derived.by(() => {
    const n = s.next;
    if (!n) return null;
    const label = n.label ?? "next episode";
    const links = `${n.links} link${n.links === 1 ? "" : "s"}`;
    switch (n.status) {
      case "waiting":
        return null;
      case "resolving":
        return `Next: ${label} · finding links${n.links ? ` (${links} so far)` : "…"}`;
      case "ready":
        return `Next: ${label} · ${links} ready`;
      case "failed":
        return `Next: ${label} · no links found`;
    }
  });

  const skipLabel = $derived(
    s.skip ? `Skip ${{ op: "opening", ed: "ending", recap: "recap" }[s.skip.type]}` : null,
  );

  function trackLabel(t: {
    id: number;
    title: string | null;
    lang: string | null;
    external: boolean;
  }) {
    return [t.lang?.toUpperCase(), t.title].filter(Boolean).join(" · ") || `Track ${t.id}`;
  }
</script>

{#if player.active}
  <div class={["bar", { hidden: player.expanded }]}>
    <div class="line"><div style="width: {pct}%"></div></div>
    <button class="info" onclick={() => (player.expanded = true)} aria-label="Open remote">
      {#if s.poster}<img src={s.poster} alt="" referrerpolicy="no-referrer" />{/if}
      <div class="text">
        <div class="title">{s.title}</div>
        <div class="sub">
          {#if busy || s.status === "error"}
            <span class={{ err: s.status === "error" }}>{s.message ?? "Loading…"}</span>
          {:else}
            {info && !isMpv ? `${info.name} · ` : ""}{s.episodeLabel ?? ""}{s.episodeLabel
              ? " · "
              : ""}{formatTime(s.position)} / {formatTime(s.duration)}
          {/if}
        </div>
      </div>
    </button>
    {#if busy}<div class="spinner"></div>{/if}
    {#if skipLabel}
      <button class="skip-btn" onclick={() => cmd("skip")}
        ><FastForward size={16} strokeWidth={1.8} aria-hidden="true" />
        {skipLabel}</button
      >
    {/if}
    <button
      class="icon-btn"
      onclick={() => cmd("toggle")}
      aria-label={s.paused ? "Play" : "Pause"}
      disabled={busy}
    >
      {#if s.paused}<Play
          size={22}
          fill="currentColor"
          strokeWidth={1.8}
          aria-hidden="true"
        />{:else}<Pause size={22} fill="currentColor" strokeWidth={1.8} aria-hidden="true" />{/if}
    </button>
    {#if s.hasNext}
      <button
        class="icon-btn hide-sm"
        class:primed={s.next?.status === "ready"}
        onclick={() => cmd("next")}
        aria-label="Next episode"
        title={nextText ?? "Next episode"}
        ><SkipForward size={22} strokeWidth={1.8} aria-hidden="true" /></button
      >
    {/if}
  </div>
{/if}

{#if player.expanded}
  <dialog
    class="remote"
    aria-label="Remote"
    {@attach modal}
    oncancel={() => (player.expanded = false)}
  >
    <header>
      <button class="icon-btn" onclick={() => (player.expanded = false)} aria-label="Close remote"
        ><ChevronDown size={22} strokeWidth={1.8} aria-hidden="true" /></button
      >
      {#if player.playing.length > 1}
        <select
          class="player-status device-pick"
          aria-label="Device"
          value={player.device}
          onchange={(e) => player.follow(e.currentTarget.value)}
        >
          {#each player.playing as d (d.id)}<option value={d.id}>{d.name}</option>{/each}
        </select>
      {:else}
        <span class="player-status"
          ><span class={["status-dot", { online: online }]}></span>{statusText}</span
        >
      {/if}
      <button class="icon-btn" onclick={openSources} aria-label="Sources"
        ><Layers size={22} strokeWidth={1.8} aria-hidden="true" /></button
      >
    </header>

    <div class="body">
      <div class="artwork">
        <div class="cover">
          {#if s.poster}<img
              src={s.poster}
              alt=""
              referrerpolicy="no-referrer"
            />{:else}<MonitorPlay size={64} strokeWidth={1.8} aria-hidden="true" />{/if}
        </div>
      </div>
      <div class="player-panel">
        <div class="meta">
          {#if s.provider && s.url}
            <a
              class="r-title"
              href={titleHref(s.provider, s.url)}
              onclick={() => (player.expanded = false)}>{s.title ?? "Nothing playing"}</a
            >
          {:else}
            <div class="r-title">{s.title ?? "Nothing playing"}</div>
          {/if}
          {#if s.episodeLabel}<div class="episode">{s.episodeLabel}</div>{/if}
          {#if s.link}<div class="faint">
              {s.link.source} · {s.link.qualityLabel}{s.link.proxied ? " · proxied" : ""}
            </div>{/if}
          {#if s.message}<div class={["msg", { err: s.status === "error" }]}>
              {s.message}
            </div>{/if}
          {#if nextText}<div class={["next", s.next?.status]}>
              {nextText}
            </div>{/if}
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
          <div class="times">
            <span>{formatTime(scrub ?? s.position)}</span><span
              >-{formatTime(s.duration - (scrub ?? s.position))}</span
            >
          </div>
        </div>

        {#if skipLabel}
          <button class="skip-btn big" onclick={() => cmd("skip")}
            ><FastForward size={18} strokeWidth={1.8} aria-hidden="true" />
            {skipLabel}</button
          >
        {/if}

        <div class="controls">
          <button
            class="icon-btn lg"
            onclick={() => cmd("prev")}
            disabled={!s.hasPrev}
            aria-label="Previous episode"
            ><SkipBack size={26} strokeWidth={1.8} aria-hidden="true" /></button
          >
          <button
            class="icon-btn lg"
            onclick={() => cmd("seek", { seconds: -10, relative: true })}
            aria-label="Back 10 seconds"
            ><RotateCcw size={28} strokeWidth={1.8} aria-hidden="true" /></button
          >
          <button
            class="play"
            onclick={() => cmd("toggle")}
            aria-label={s.paused ? "Play" : "Pause"}
          >
            {#if busy}<div class="spinner big"></div>{:else}{#if s.paused}<Play
                  size={34}
                  fill="currentColor"
                  strokeWidth={1.8}
                  aria-hidden="true"
                />{:else}<Pause
                  size={34}
                  fill="currentColor"
                  strokeWidth={1.8}
                  aria-hidden="true"
                />{/if}{/if}
          </button>
          <button
            class="icon-btn lg"
            onclick={() => cmd("seek", { seconds: 10, relative: true })}
            aria-label="Forward 10 seconds"
            ><RotateCw size={28} strokeWidth={1.8} aria-hidden="true" /></button
          >
          <button
            class="icon-btn lg"
            onclick={() => cmd("next")}
            disabled={!s.hasNext}
            aria-label="Next episode"
            ><SkipForward size={26} strokeWidth={1.8} aria-hidden="true" /></button
          >
        </div>

        <div class="tracks">
          <label>
            <span class="track-heading"
              ><Volume2 size={17} strokeWidth={1.8} aria-hidden="true" /> Audio</span
            >
            <select
              onchange={(e) => cmd("track", { type: "audio", id: e.currentTarget.value })}
              disabled={!s.audioTracks.length}
            >
              {#each s.audioTracks as t (t.id)}<option value={String(t.id)} selected={t.selected}
                  >{trackLabel(t)}</option
                >{/each}
              {#if !s.audioTracks.length}<option>Audio</option>{/if}
            </select>
          </label>
          <label>
            <span class="track-heading"
              ><Captions size={17} strokeWidth={1.8} aria-hidden="true" /> Subtitles</span
            >
            <select onchange={(e) => cmd("track", { type: "sub", id: e.currentTarget.value })}>
              <option value="no" selected={!s.subTracks.some((t) => t.selected)}
                >Subtitles off</option
              >
              {#each s.subTracks as t (t.id)}<option value={String(t.id)} selected={t.selected}
                  >{trackLabel(t)}</option
                >{/each}
            </select>
          </label>
          <label class="vol">
            <span class="track-heading"
              ><Volume1 size={17} strokeWidth={1.8} aria-hidden="true" /> Volume</span
            >
            <input
              type="range"
              min="0"
              max={isMpv ? 130 : 100}
              value={s.volume}
              onchange={(e) => cmd("volume", { value: +e.currentTarget.value })}
              aria-label="Volume"
            />
          </label>
        </div>

        <div class="actions">
          <button class="btn" onclick={openSources}
            ><Layers size={18} strokeWidth={1.8} aria-hidden="true" /> Sources</button
          >
          <button class="btn" onclick={() => cmd("stop")}
            ><Square size={18} strokeWidth={1.8} aria-hidden="true" /> Stop</button
          >
        </div>
      </div>
    </div>
  </dialog>
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
    animation: bar-in 280ms cubic-bezier(0.16, 1, 0.3, 1) both;
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
    transition: width 250ms ease-out;
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
    transition:
      background-color 150ms ease,
      transform 150ms ease;
  }
  .icon-btn:hover:not(:disabled) {
    background: var(--surface-3);
  }
  .icon-btn:disabled {
    opacity: 0.35;
    cursor: default;
  }
  .icon-btn:active:not(:disabled) {
    transform: scale(0.92);
  }
  .icon-btn.lg {
    width: 56px;
    height: 56px;
  }
  .remote {
    position: fixed;
    inset: 0;
    width: 100%;
    height: 100dvh;
    max-width: none;
    max-height: none;
    margin: 0;
    padding: 0;
    border: 0;
    color: var(--text);
    z-index: 50;
    background: radial-gradient(ellipse at 28% 20%, #25232a 0%, #15171d 40%, var(--bg) 75%);
    display: flex;
    flex-direction: column;
    padding-bottom: var(--safe-b);
    overflow-y: auto;
    overscroll-behavior: contain;
    animation: remote-in 360ms cubic-bezier(0.16, 1, 0.3, 1) both;
  }
  @keyframes bar-in {
    from {
      opacity: 0;
      transform: translateY(12px);
    }
  }
  @keyframes remote-in {
    from {
      opacity: 0;
      transform: translateY(24px);
    }
  }
  header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 16px clamp(16px, 3vw, 40px);
    font-size: 13px;
  }
  header .icon-btn {
    background: rgb(255 255 255 / 0.05);
  }
  .player-status {
    display: flex;
    align-items: center;
    gap: 8px;
    color: var(--muted);
    font-weight: 600;
  }
  .device-pick {
    width: auto;
    max-width: 55vw;
    min-height: 36px;
    padding: 0 10px;
    background: var(--surface-2);
    border: 1px solid var(--line);
    border-radius: 999px;
  }
  .status-dot {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: var(--muted);
  }
  .status-dot.online {
    background: var(--good);
  }
  .body {
    width: min(100%, 1020px);
    margin: auto;
    padding: 30px 36px 90px;
    display: grid;
    grid-template-columns: minmax(220px, 320px) minmax(0, 1fr);
    align-items: center;
    gap: clamp(48px, 8vw, 110px);
  }
  .cover {
    width: 100%;
    aspect-ratio: 2 / 3;
    border-radius: 14px;
    overflow: hidden;
    background: var(--surface-2);
    display: grid;
    place-items: center;
    color: var(--faint);
    box-shadow: 0 24px 70px rgb(0 0 0 / 0.45);
  }
  .cover img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
  .player-panel {
    min-width: 0;
    display: flex;
    flex-direction: column;
  }
  .meta {
    display: flex;
    flex-direction: column;
    gap: 5px;
    margin-bottom: 34px;
  }
  .r-title {
    font-size: clamp(28px, 3vw, 40px);
    line-height: 1.15;
    font-weight: 750;
    letter-spacing: -0.03em;
    overflow-wrap: anywhere;
  }
  a.r-title:hover {
    color: var(--accent);
  }
  .episode {
    color: var(--text);
    font-size: 15px;
  }
  .faint {
    color: var(--muted);
    font-size: 13px;
  }
  .msg {
    margin-top: 6px;
    font-size: 14px;
    color: var(--accent);
  }
  .msg.err {
    color: var(--danger);
  }
  .next {
    margin-top: 4px;
    font-size: 13px;
    color: var(--muted);
  }
  .next.ready {
    color: var(--good);
  }
  .next.failed {
    color: var(--danger);
  }
  .icon-btn.primed {
    color: var(--good);
  }
  .skip-btn {
    flex-shrink: 0;
    display: inline-flex;
    align-items: center;
    gap: 6px;
    height: 36px;
    padding: 0 12px;
    border-radius: 999px;
    border: 1px solid var(--line);
    background: var(--surface-3);
    font-size: 13px;
    font-weight: 650;
    white-space: nowrap;
    transition:
      background-color 150ms ease,
      transform 150ms ease;
    animation: bar-in 200ms ease-out both;
  }
  .skip-btn:hover {
    background: var(--accent-soft);
    color: var(--accent);
  }
  .skip-btn:active {
    transform: scale(0.95);
  }
  .skip-btn.big {
    align-self: flex-end;
    height: 44px;
    padding: 0 18px;
    margin-top: 14px;
    font-size: 14px;
  }
  .seek input,
  .vol input {
    width: 100%;
    padding: 0;
    accent-color: var(--accent);
    background: none;
    border: 0;
    cursor: pointer;
  }
  .seek input:disabled {
    cursor: default;
  }
  .times {
    display: flex;
    justify-content: space-between;
    margin-top: 6px;
    font-size: 12px;
    color: var(--muted);
    font-variant-numeric: tabular-nums;
  }
  .controls {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    margin: 20px 0 32px;
  }
  .play {
    width: 72px;
    height: 72px;
    flex-shrink: 0;
    border-radius: 50%;
    background: var(--accent);
    color: var(--accent-ink);
    display: grid;
    place-items: center;
    transition:
      transform 0.15s,
      filter 0.15s;
  }
  .play:hover {
    filter: brightness(1.08);
    transform: scale(1.04);
  }
  .play:active {
    transform: scale(0.92);
  }
  .spinner.big {
    width: 30px;
    height: 30px;
    border-color: rgb(0 0 0 / 0.2);
    border-top-color: var(--accent-ink);
  }
  .tracks {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 18px 14px;
    padding-top: 24px;
    border-top: 1px solid var(--line);
  }
  .tracks label {
    display: flex;
    flex-direction: column;
    gap: 8px;
    min-width: 0;
  }
  .track-heading {
    display: flex;
    align-items: center;
    gap: 7px;
    color: var(--muted);
    font-size: 12px;
    font-weight: 650;
  }
  .tracks select {
    width: 100%;
    min-height: 44px;
    font-size: 13px;
    text-overflow: ellipsis;
  }
  .tracks select:disabled {
    opacity: 0.6;
  }
  .vol {
    grid-column: 1 / -1;
  }
  .vol input {
    margin: 4px 0;
  }
  .actions {
    display: flex;
    gap: 10px;
    margin-top: 28px;
  }
  .actions .btn {
    min-width: 112px;
  }
  @media (max-width: 700px) {
    header {
      flex-shrink: 0;
      padding: max(8px, env(safe-area-inset-top)) 16px 8px;
    }
    .body {
      display: flex;
      flex: 0 0 auto;
      flex-direction: column;
      gap: clamp(10px, 2dvh, 20px);
      width: min(100%, 520px);
      padding: 4px 24px 16px;
      margin: auto;
    }
    .artwork {
      width: min(48vw, 210px, max(110px, 66.667dvh - 346.67px));
      flex-shrink: 0;
    }
    .player-panel {
      width: 100%;
    }
    .meta {
      text-align: center;
      gap: 2px;
      margin-bottom: 12px;
    }
    .r-title {
      font-size: 24px;
    }
    .times {
      margin-top: 2px;
    }
    .controls {
      margin: 8px 0 14px;
    }
    .play {
      width: 64px;
      height: 64px;
    }
    .tracks {
      gap: 10px 12px;
      padding-top: 14px;
    }
    .tracks label {
      gap: 4px;
    }
    .tracks select {
      font-size: 16px;
    }
    .vol input {
      margin: 2px 0;
    }
    .actions {
      justify-content: center;
      margin-top: 14px;
    }
  }
  @media (max-width: 380px) {
    .body {
      padding-inline: 18px;
    }
    .icon-btn.lg {
      width: 44px;
      height: 48px;
    }
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
  @media (prefers-reduced-motion: reduce) {
    .bar,
    .remote,
    .skip-btn {
      animation: none;
    }
    .icon-btn:active:not(:disabled) {
      transform: none;
    }
  }
</style>
