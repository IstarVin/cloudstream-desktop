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
  const busy = $derived(s.status === "resolving");
  const playbackText = $derived(
    busy
      ? "Loading"
      : s.status === "error"
        ? "Playback error"
        : s.status === "idle"
          ? "Stopped"
          : s.paused
            ? "Paused"
            : "Playing",
  );
  const statusText = $derived(
    !player.connected
      ? "Device disconnected"
      : isMpv
        ? s.mpvRunning
          ? `${playbackText} in mpv`
          : "mpv is closed"
        : info
          ? `${playbackText} on ${info.name}`
          : "Device disconnected",
  );
  const available = $derived(online && player.connected);
  const canControl = $derived(available && !busy);
  const canSource = $derived(!!(s.provider && s.url && s.data));
  const pct = $derived(
    s.duration > 0 ? Math.min(100, Math.max(0, (s.position / s.duration) * 100)) : 0,
  );
  // Keep local feedback responsive while the remote device acknowledges a change.
  let scrub = $state<number | null>(null);
  let volume = $state<number | null>(null);
  const position = $derived(scrub ?? s.position);
  const seekPct = $derived(
    s.duration > 0 ? Math.min(100, Math.max(0, (position / s.duration) * 100)) : 0,
  );
  const maxVolume = $derived(isMpv ? 130 : 100);
  const shownVolume = $derived(volume ?? s.volume);
  let failedPoster = $state<string | null>(null);

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
      <button class="skip-btn" disabled={!canControl} onclick={() => cmd("skip")}
        ><FastForward size={16} strokeWidth={1.8} aria-hidden="true" />
        {skipLabel}</button
      >
    {/if}
    <button
      class="icon-btn"
      onclick={() => cmd("toggle")}
      aria-label={s.paused ? "Play" : "Pause"}
      disabled={!canControl}
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
        class={["icon-btn hide-sm", { primed: s.next?.status === "ready" }]}
        onclick={() => cmd("next")}
        aria-label="Next episode"
        disabled={!canControl}
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
      <span class="remote-label"><MonitorPlay size={17} aria-hidden="true" /> Playback remote</span>
      <span class="player-status" role="status"
        ><span
          class={["status-dot", { online: available, paused: available && (s.paused || busy) }]}
        ></span>{statusText}</span
      >
    </header>

    <div class="body">
      <div class="artwork">
        <div class="cover">
          {#if s.poster && failedPoster !== s.poster}<img
              onerror={() => (failedPoster = s.poster ?? null)}
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
          {#if s.link}<div class="source-meta">
              <span>{s.link.source}</span>
              {#if s.link.qualityLabel}<span class="quality">{s.link.qualityLabel}</span>{/if}
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
            value={position}
            style:--fill={`${seekPct}%`}
            aria-valuetext={`${formatTime(position)} of ${formatTime(s.duration)}`}
            oninput={(e) => (scrub = +e.currentTarget.value)}
            onchange={(e) => {
              cmd("seek", { seconds: +e.currentTarget.value });
              scrub = null;
            }}
            aria-label="Seek"
            disabled={!canControl || s.duration <= 0}
          />
          <div class="times">
            <span class="elapsed">{formatTime(position)}</span><span
              >{formatTime(Math.max(0, s.duration - position))} remaining</span
            >
          </div>
        </div>

        {#if skipLabel}
          <button class="skip-btn big" disabled={!canControl} onclick={() => cmd("skip")}
            ><FastForward size={18} strokeWidth={1.8} aria-hidden="true" />
            {skipLabel}</button
          >
        {/if}

        <div class="controls">
          <button
            class="icon-btn lg"
            onclick={() => cmd("prev")}
            disabled={!canControl || !s.hasPrev}
            aria-label="Previous episode"
            ><SkipBack size={26} strokeWidth={1.8} aria-hidden="true" /></button
          >
          <button
            class="icon-btn lg"
            onclick={() => cmd("seek", { seconds: -10, relative: true })}
            aria-label="Back 10 seconds"
            title="Back 10 seconds"
            disabled={!canControl || s.duration <= 0}
            ><RotateCcw size={26} strokeWidth={1.8} aria-hidden="true" /><span class="seek-label"
              >10s</span
            ></button
          >
          <button
            class="play"
            disabled={!canControl}
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
            title="Forward 10 seconds"
            disabled={!canControl || s.duration <= 0}
            ><RotateCw size={26} strokeWidth={1.8} aria-hidden="true" /><span class="seek-label"
              >10s</span
            ></button
          >
          <button
            class="icon-btn lg"
            onclick={() => cmd("next")}
            disabled={!canControl || !s.hasNext}
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
              disabled={!canControl || !s.audioTracks.length}
            >
              {#each s.audioTracks as t (t.id)}<option value={String(t.id)} selected={t.selected}
                  >{trackLabel(t)}</option
                >{/each}
              {#if !s.audioTracks.length}<option>No audio tracks</option>{/if}
            </select>
          </label>
          <label>
            <span class="track-heading"
              ><Captions size={17} strokeWidth={1.8} aria-hidden="true" /> Subtitles</span
            >
            <select
              disabled={!canControl || !s.subTracks.length}
              onchange={(e) => cmd("track", { type: "sub", id: e.currentTarget.value })}
            >
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
              ><Volume1 size={17} strokeWidth={1.8} aria-hidden="true" /> Volume
              <span class="volume-value">{Math.round(shownVolume)}%</span></span
            >
            <input
              type="range"
              min="0"
              max={maxVolume}
              value={shownVolume}
              style:--fill={`${Math.min(100, Math.max(0, (shownVolume / maxVolume) * 100))}%`}
              aria-valuetext={`${Math.round(shownVolume)} percent`}
              disabled={!canControl}
              oninput={(e) => (volume = +e.currentTarget.value)}
              onchange={(e) => {
                cmd("volume", { value: +e.currentTarget.value });
                volume = null;
              }}
              aria-label="Volume"
            />
          </label>
        </div>

        <div class="actions">
          <button class="btn source-action" disabled={!canSource} onclick={openSources}
            ><Layers size={18} strokeWidth={1.8} aria-hidden="true" /> Sources</button
          >
          <button class="btn stop-action" disabled={!available} onclick={() => cmd("stop")}
            ><Square size={18} strokeWidth={1.8} aria-hidden="true" /> Stop playback</button
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
    background: var(--bg);
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
    gap: 12px;
    flex-shrink: 0;
    padding: 20px clamp(20px, 4vw, 56px);
    font-size: 13px;
  }
  header .icon-btn {
    background: rgb(255 255 255 / 0.05);
  }
  .remote-label {
    display: flex;
    align-items: center;
    gap: 8px;
    font-weight: 600;
  }
  .player-status {
    margin-left: auto;
    min-width: 0;
    overflow-wrap: anywhere;
    display: flex;
    align-items: center;
    gap: 8px;
    color: var(--muted);
    font-weight: 600;
  }
  .status-dot {
    flex-shrink: 0;
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: var(--muted);
  }
  .status-dot.online {
    background: var(--good);
  }
  .status-dot.paused {
    background: var(--accent);
  }
  .body {
    width: min(100%, 1120px);
    margin: auto;
    padding: 36px 48px 72px;
    display: grid;
    grid-template-columns: minmax(220px, 320px) minmax(0, 1fr);
    align-items: center;
    gap: clamp(36px, 6vw, 80px);
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
    margin-bottom: 28px;
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
  .source-meta {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 8px;
    margin-top: 8px;
    color: var(--muted);
    font-size: 13px;
    overflow-wrap: anywhere;
  }
  .quality {
    padding: 2px 7px;
    border: 1px solid var(--line);
    border-radius: 4px;
    color: var(--text);
    font-size: 11px;
    font-weight: 650;
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
  .skip-btn:hover:not(:disabled) {
    background: var(--accent-soft);
    color: var(--accent);
  }
  .skip-btn:disabled {
    cursor: default;
    opacity: 0.5;
  }
  .skip-btn:active:not(:disabled) {
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
    appearance: none;
    display: block;
    width: 100%;
    height: 28px;
    padding: 0;
    margin: 0;
    border: 0;
    border-radius: 0;
    background: transparent;
    cursor: pointer;
  }
  .seek input::-webkit-slider-runnable-track,
  .vol input::-webkit-slider-runnable-track {
    height: 4px;
    border-radius: 4px;
    background: linear-gradient(to right, var(--accent) var(--fill), var(--surface-3) var(--fill));
  }
  .seek input::-moz-range-track,
  .vol input::-moz-range-track {
    height: 4px;
    border-radius: 4px;
    background: linear-gradient(to right, var(--accent) var(--fill), var(--surface-3) var(--fill));
  }
  .seek input::-webkit-slider-thumb,
  .vol input::-webkit-slider-thumb {
    appearance: none;
    width: 14px;
    height: 14px;
    margin-top: -5px;
    border: 0;
    border-radius: 50%;
    background: var(--accent);
  }
  .seek input::-moz-range-thumb,
  .vol input::-moz-range-thumb {
    width: 14px;
    height: 14px;
    border: 0;
    border-radius: 50%;
    background: var(--accent);
  }
  input[type="range"]:disabled {
    cursor: default;
    opacity: 0.4;
  }
  .elapsed {
    color: var(--text);
  }
  .seek-label {
    font-size: 11px;
    line-height: 1;
    font-weight: 600;
  }
  .controls .icon-btn.lg {
    align-content: center;
    gap: 3px;
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
    justify-content: center;
    gap: clamp(12px, 2vw, 28px);
    margin: 24px 0 32px;
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
  .play:hover:not(:disabled) {
    filter: brightness(1.08);
    transform: scale(1.04);
  }
  .play:active:not(:disabled) {
    transform: scale(0.92);
  }
  .play:disabled {
    cursor: default;
    opacity: 0.5;
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
    appearance: none;
    width: 100%;
    min-height: 44px;
    font-size: 13px;
    text-overflow: ellipsis;
    white-space: nowrap;
    overflow: hidden;
  }
  .tracks select:disabled {
    opacity: 0.6;
  }
  .vol {
    grid-column: 1 / -1;
  }
  .volume-value {
    margin-left: auto;
    color: var(--text);
    font-variant-numeric: tabular-nums;
    font-weight: 500;
  }
  .actions {
    display: flex;
    gap: 10px;
    margin-top: 28px;
  }
  .actions .btn {
    min-width: 112px;
    font-size: 13px;
  }
  .actions .stop-action {
    margin-left: auto;
    color: var(--muted);
    border-color: transparent;
    background: transparent;
  }
  .actions .stop-action:hover:not(:disabled) {
    background: var(--surface-2);
    color: var(--text);
  }
  @media (max-width: 700px) {
    header {
      flex-shrink: 0;
      padding: max(8px, env(safe-area-inset-top)) 16px 8px;
    }
    .remote-label {
      display: none;
    }
    .player-status {
      font-size: 12px;
      text-align: right;
    }
    .source-meta {
      justify-content: center;
      margin-top: 6px;
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
      justify-content: space-between;
      gap: 4px;
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
