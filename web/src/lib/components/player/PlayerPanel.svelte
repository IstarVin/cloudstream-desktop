<script lang="ts" module>
  export type Panel = "tracks" | "speed" | "episodes";
</script>

<script lang="ts">
  import { Check, X } from "@lucide/svelte";
  import { episodeLabel } from "../../api";
  import { sources } from "../../sources.svelte";
  import { webPlayer } from "../../web-player.svelte";

  let { panel, onclose }: { panel: Panel; onclose: () => void } = $props();

  const SPEEDS = [0.25, 0.5, 0.75, 1, 1.25, 1.5, 1.75, 2, 2.5, 3];
  const title = $derived(
    { tracks: "Audio & subtitles", speed: "Speed", episodes: "Episodes" }[panel],
  );
  const p = webPlayer;
  const noSubs = $derived(p.subtitlesOff || (!p.subtitle && p.embeddedSub < 0));
  const loaded = $derived(new Set(p.loadedSubs.map((s) => s.id)));
  let current = $state<HTMLElement | null>(null);
  $effect(() => current?.scrollIntoView({ block: "center" }));
</script>

<div class="panel" role="dialog" aria-label={title} tabindex="-1">
  <header>
    <h2>{title}</h2>
    <button class="close" onclick={onclose} aria-label="Close"
      ><X size={20} strokeWidth={1.8} aria-hidden="true" /></button
    >
  </header>
  <div class="body">
    {#if panel === "tracks"}
      {#if p.levels.length > 1}
        <h3>Quality</h3>
        <button class={["row", { on: p.level < 0 }]} onclick={() => p.setLevel(-1)}>
          <span>Auto</span>{#if p.level < 0}<Check size={18} aria-hidden="true" />{/if}
        </button>
        {#each p.levels as l (l.id)}
          <button class={["row", { on: p.level === l.id }]} onclick={() => p.setLevel(l.id)}>
            <span>{l.label}</span>{#if p.level === l.id}<Check size={18} aria-hidden="true" />{/if}
          </button>
        {/each}
      {/if}
      {#if p.audioTracks.length > 1}
        <h3>Audio</h3>
        {#each p.audioTracks as a (a.id)}
          <button class={["row", { on: a.selected }]} onclick={() => p.setAudio(a.id)}>
            <span>{a.label}</span>{#if a.selected}<Check size={18} aria-hidden="true" />{/if}
          </button>
        {/each}
      {/if}
      <h3>Subtitles</h3>
      <button class={["row", { on: noSubs }]} onclick={() => p.subtitlesOffNow()}>
        <span>Off</span>{#if noSubs}<Check size={18} aria-hidden="true" />{/if}
      </button>
      {#each p.embeddedSubs as e (e.id)}
        <button
          class={["row", { on: p.embeddedSub === e.id }]}
          onclick={() => p.selectEmbedded(e.id)}
        >
          <span>{e.label} <small>in stream</small></span>{#if p.embeddedSub === e.id}<Check
              size={18}
              aria-hidden="true"
            />{/if}
        </button>
      {/each}
      {#each sources.subtitles as s (s.id)}
        <button class={["row", { on: p.subtitle === s.id }]} onclick={() => p.selectSubtitle(s)}>
          <span
            >{s.lang}
            {#if s.format === "ttml"}<small>not supported here</small
              >{:else if !loaded.has(s.id)}<small>loads on select</small>{/if}</span
          >{#if p.subtitle === s.id}<Check size={18} aria-hidden="true" />{/if}
        </button>
      {/each}
      {#if !sources.subtitles.length && !p.embeddedSubs.length}
        <p class="muted">
          {sources.done ? "No subtitles for this episode." : "Looking for subtitles…"}
        </p>
      {/if}
    {:else if panel === "speed"}
      {#each SPEEDS as s (s)}
        <button class={["row", { on: p.rate === s }]} onclick={() => p.setRate(s)}>
          <span>{s === 1 ? "Normal" : `${s}×`}</span>{#if p.rate === s}<Check
              size={18}
              aria-hidden="true"
            />{/if}
        </button>
      {/each}
    {:else if p.item}
      {#each p.item.episodes as e, i (e.data)}
        {@const now = i === p.item.index}
        {@const prog = e.progress}
        <button
          class={["row", "ep", { on: now }]}
          onclick={() => {
            p.episodeAt(i);
            onclose();
          }}
          {@attach (el) => {
            if (now) current = el;
          }}
        >
          <span class="ep-text">
            <span>{episodeLabel(e, i)}</span>
            {#if prog?.watched}<small>Watched</small>{:else if prog && prog.duration > 0}<span
                class="bar"
                ><span style:width="{(prog.position / prog.duration) * 100}%"></span></span
              >{/if}
          </span>
          {#if now}<small class="playing">Playing</small>{/if}
        </button>
      {/each}
    {/if}
  </div>
</div>

<style>
  .panel {
    position: absolute;
    top: 12px;
    right: 12px;
    bottom: 12px;
    width: min(360px, calc(100% - 24px));
    display: flex;
    flex-direction: column;
    background: rgb(22 24 30 / 0.94);
    backdrop-filter: blur(12px);
    border: 1px solid var(--line);
    border-radius: 16px;
    z-index: 5;
    animation: in 200ms cubic-bezier(0.16, 1, 0.3, 1) both;
  }
  @keyframes in {
    from {
      opacity: 0;
      transform: translateX(16px);
    }
  }
  header {
    display: flex;
    align-items: center;
    padding: 8px 8px 4px 16px;
  }
  h2 {
    flex: 1;
    font-size: 17px;
    margin: 0;
  }
  h3 {
    font-size: 12px;
    font-weight: 700;
    text-transform: uppercase;
    letter-spacing: 0.06em;
    color: var(--faint);
    margin: 16px 8px 6px;
  }
  h3:first-child {
    margin-top: 4px;
  }
  .close {
    width: 40px;
    height: 40px;
    display: grid;
    place-items: center;
    border-radius: 50%;
  }
  .close:hover {
    background: var(--surface-2);
  }
  .body {
    overflow-y: auto;
    padding: 4px 8px 12px;
    flex: 1;
  }
  .row {
    width: 100%;
    min-height: 44px;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    padding: 8px 10px;
    border-radius: var(--radius-sm);
    text-align: left;
    transition: background-color 150ms ease;
  }
  .row:hover {
    background: var(--surface-2);
  }
  .row.on {
    background: var(--accent-soft);
    color: var(--text);
  }
  .row :global(svg) {
    color: var(--accent);
  }
  small {
    color: var(--muted);
    font-size: 12px;
    margin-left: 4px;
  }
  .ep-text {
    display: flex;
    flex-direction: column;
    gap: 5px;
    min-width: 0;
  }
  .ep-text small {
    margin: 0;
  }
  .playing {
    color: var(--accent);
    flex-shrink: 0;
  }
  .bar {
    display: block;
    width: 120px;
    height: 3px;
    border-radius: 2px;
    background: var(--surface-3);
    overflow: hidden;
  }
  .bar span {
    display: block;
    height: 100%;
    background: var(--accent);
  }
  .muted {
    padding: 4px 10px;
  }
  @media (prefers-reduced-motion: reduce) {
    .panel {
      animation: none;
    }
  }
</style>
