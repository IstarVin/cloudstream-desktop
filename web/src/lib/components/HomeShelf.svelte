<script lang="ts">
  import type { Snippet } from "svelte";
  import { ChevronLeft, ChevronRight } from "@lucide/svelte";

  let {
    title,
    description,
    href,
    children,
  }: {
    title: string;
    description?: string;
    href?: string;
    children: Snippet;
  } = $props();

  const id = $props.id();
  let track: HTMLDivElement;
  let canBack = $state(false);
  let canForward = $state(false);

  function measure() {
    if (!track) return;
    canBack = track.scrollLeft > 2;
    canForward = track.scrollLeft + track.clientWidth < track.scrollWidth - 2;
  }
  function observe(element: HTMLDivElement) {
    track = element;
    const resize = new ResizeObserver(measure);
    resize.observe(element);
    const mutations = new MutationObserver(measure);
    mutations.observe(element, { childList: true, subtree: true });
    measure();
    return () => {
      resize.disconnect();
      mutations.disconnect();
    };
  }
  function scroll(direction: number) {
    track.scrollBy({
      left: direction * track.clientWidth * 0.85,
      behavior: matchMedia("(prefers-reduced-motion: reduce)").matches ? "instant" : "smooth",
    });
  }
</script>

<section class="shelf" aria-labelledby="{id}-heading">
  <div class="heading">
    <div class="heading-text">
      <h2 id="{id}-heading">{title}</h2>
      {#if description}<p>{description}</p>{/if}
    </div>
    <div class="actions">
      {#if href}<a class="view-all" {href} aria-label={`View all ${title}`}
          >View all <ChevronRight size={16} aria-hidden="true" /></a
        >{/if}
      <div class="arrows">
        <button
          aria-label={`Scroll ${title} backward`}
          aria-controls="{id}-track"
          disabled={!canBack}
          onclick={() => scroll(-1)}><ChevronLeft size={19} aria-hidden="true" /></button
        >
        <button
          aria-label={`Scroll ${title} forward`}
          aria-controls="{id}-track"
          disabled={!canForward}
          onclick={() => scroll(1)}><ChevronRight size={19} aria-hidden="true" /></button
        >
      </div>
    </div>
  </div>
  <div class="scroll-x" id="{id}-track" onscroll={measure} {@attach observe}>
    {@render children()}
  </div>
</section>

<style>
  .shelf {
    margin-top: 36px;
    min-width: 0;
  }
  .heading {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    margin-bottom: 14px;
  }
  .heading-text {
    min-width: 0;
  }
  h2 {
    font-size: clamp(20px, 2vw, 24px);
    font-weight: 650;
    letter-spacing: -0.025em;
    overflow-wrap: anywhere;
  }
  p {
    margin: 5px 0 0;
    color: var(--muted);
    font-size: 13px;
  }
  .actions,
  .arrows {
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .actions {
    flex-shrink: 0;
    gap: 16px;
  }
  .view-all {
    display: inline-flex;
    align-items: center;
    gap: 3px;
    min-height: 44px;
    color: var(--muted);
    font-size: 13px;
    font-weight: 600;
  }
  .view-all:hover {
    color: var(--accent);
  }
  button {
    display: grid;
    place-items: center;
    width: 44px;
    height: 44px;
    border: 1px solid var(--line);
    border-radius: 50%;
    transition:
      background 160ms,
      border-color 160ms;
  }
  button:hover:not(:disabled) {
    background: var(--surface-2);
    border-color: var(--muted);
  }
  button:disabled {
    opacity: 0.3;
    cursor: default;
  }
  .scroll-x {
    gap: 18px;
    scroll-padding-inline-start: 8px;
    padding-top: 6px;
    padding-bottom: 14px;
  }
  @media (max-width: 599px) {
    .arrows {
      display: none;
    }
    .shelf {
      margin-top: 28px;
    }
    .scroll-x {
      gap: 12px;
    }
    .heading {
      gap: 12px;
    }
  }
  @media (prefers-reduced-motion: reduce) {
    button {
      transition: none;
    }
  }
</style>
