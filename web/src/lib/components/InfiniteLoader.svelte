<script lang="ts">
  let {
    hasNext,
    loading,
    error = null,
    horizontal = false,
    onloadmore,
  }: {
    hasNext: boolean;
    loading: boolean;
    error?: string | null;
    horizontal?: boolean;
    onloadmore: () => void;
  } = $props();

  function observeEnd(element: HTMLElement) {
    if (!hasNext || loading || error) return;
    // The viewport root also respects carousel clipping and avoids loading offscreen rows.
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting) && hasNext && !loading && !error) {
        observer.disconnect();
        onloadmore();
      }
    }, { rootMargin: "200px 0px" });
    observer.observe(element);
    return () => observer.disconnect();
  }
</script>

{#if hasNext}
  <div class={["load-more", { horizontal }]} {@attach observeEnd}>
    {#if loading}
      <span class="loading" role="status"><span class="spinner" aria-hidden="true"></span> Loading more titles…</span>
    {:else}
      {#if error}<p class="load-error" role="alert">Couldn't load more titles: {error}</p>{/if}
      <button class="btn" onclick={onloadmore}>{error ? "Try again" : "Load more"}</button>
    {/if}
  </div>
{/if}

<style>
  .load-more {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 12px;
    min-height: 64px;
  }
  .horizontal {
    flex: 0 0 160px;
    align-self: stretch;
    scroll-snap-align: end;
    text-align: center;
  }
  .loading {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    color: var(--muted);
    font-size: 14px;
  }
  .horizontal .loading { flex-direction: column; }
  .load-error { margin: 0; color: var(--danger); }
</style>
