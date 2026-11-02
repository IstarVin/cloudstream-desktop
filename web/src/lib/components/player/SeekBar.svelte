<script lang="ts">
  import { formatTime } from "../../api";
  import type { SkipRange } from "../../types";

  let {
    position,
    duration,
    buffered,
    skips,
    onseek,
    onscrub,
  }: {
    position: number;
    duration: number;
    buffered: number;
    skips: SkipRange[];
    onseek: (seconds: number) => void;
    /** The drag position while scrubbing, null on release. */
    onscrub?: (seconds: number | null) => void;
  } = $props();

  let drag = $state<number | null>(null);
  let hover = $state<{ x: number; t: number } | null>(null);
  const shown = $derived(drag ?? position);
  const pct = (t: number) => (duration > 0 ? Math.min(100, (t / duration) * 100) : 0);

  function hoverAt(e: PointerEvent) {
    if (e.pointerType !== "mouse" || duration <= 0) return;
    const r = (e.currentTarget as HTMLElement).parentElement!.getBoundingClientRect();
    const f = Math.min(1, Math.max(0, (e.clientX - r.left) / r.width));
    hover = { x: f * 100, t: f * duration };
  }
</script>

<div class="seek">
  <div class="track">
    <div class="buffered" style:width="{pct(buffered)}%"></div>
    {#each skips as s (s.start)}
      <div
        class="skip"
        style:left="{pct(s.start)}%"
        style:width="{pct(s.end) - pct(s.start)}%"
      ></div>
    {/each}
    <div class="played" style:width="{pct(shown)}%"></div>
    <div class="thumb" style:left="{pct(shown)}%"></div>
  </div>
  {#if hover}
    <div class="tip" style:left="{hover.x}%">{formatTime(hover.t)}</div>
  {/if}
  <input
    type="range"
    min="0"
    max={duration || 1}
    step="0.1"
    value={shown}
    aria-label="Seek"
    aria-valuetext={`${formatTime(shown)} of ${formatTime(duration)}`}
    disabled={duration <= 0}
    onpointermove={hoverAt}
    onpointerleave={() => (hover = null)}
    oninput={(e) => {
      drag = Number(e.currentTarget.value);
      onscrub?.(drag);
    }}
    onchange={(e) => {
      onseek(Number(e.currentTarget.value));
      drag = null;
      onscrub?.(null);
    }}
    onkeydown={(e) => e.stopPropagation()}
  />
</div>

<style>
  .seek {
    position: relative;
    height: 28px;
    display: flex;
    align-items: center;
    touch-action: none;
  }
  .track {
    position: relative;
    width: 100%;
    height: 4px;
    border-radius: 2px;
    background: rgb(255 255 255 / 0.22);
    transition: height 120ms ease;
  }
  .seek:hover .track,
  .seek:focus-within .track {
    height: 6px;
  }
  .buffered,
  .played,
  .skip {
    position: absolute;
    top: 0;
    bottom: 0;
    left: 0;
    border-radius: inherit;
  }
  .buffered {
    background: rgb(255 255 255 / 0.35);
  }
  .skip {
    background: var(--good);
    opacity: 0.8;
    border-radius: 0;
  }
  .played {
    background: var(--accent);
  }
  .thumb {
    position: absolute;
    top: 50%;
    width: 14px;
    height: 14px;
    margin: -7px 0 0 -7px;
    border-radius: 50%;
    background: var(--accent);
    box-shadow: 0 0 0 4px rgb(255 138 76 / 0.25);
  }
  .tip {
    position: absolute;
    bottom: 26px;
    transform: translateX(-50%);
    padding: 3px 7px;
    border-radius: 6px;
    background: rgb(0 0 0 / 0.8);
    font-size: 12.5px;
    font-variant-numeric: tabular-nums;
    pointer-events: none;
  }
  input {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    margin: 0;
    opacity: 0;
    cursor: pointer;
  }
  input:disabled {
    cursor: default;
  }
  .seek:has(input:focus-visible) .thumb {
    outline: 2px solid var(--text);
    outline-offset: 2px;
  }
</style>
