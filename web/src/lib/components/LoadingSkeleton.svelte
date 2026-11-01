<script lang="ts">
  let {
    variant = "row",
    horizontal = false,
    showProviders = true,
  }: {
    variant?: "home" | "row" | "title";
    horizontal?: boolean;
    showProviders?: boolean;
  } = $props();
</script>

{#snippet cards()}
  <div class={["cards", { horizontal }]}>
    {#each Array(8) as _, i (i)}
      <div class="card">
        <div class="bone artwork"></div>
        <div class="bone card-title"></div>
        <div class="bone card-meta"></div>
      </div>
    {/each}
  </div>
{/snippet}

{#if variant === "title"}
  <div class="page detail-loading" role="status" aria-label="Loading title">
    <div aria-hidden="true">
      <div class="bone back"></div>
      <div class="detail-head">
        <div class="bone detail-poster"></div>
        <div class="detail-info">
          <div class="bone title"></div>
          <div class="bone facts"></div>
          <div class="bone tags"></div>
          <div class="buttons">
            <div class="bone button"></div>
            <div class="bone button secondary"></div>
          </div>
        </div>
      </div>
      <div class="description">
        <div class="bone line"></div>
        <div class="bone line short"></div>
      </div>
      <div class="bone heading"></div>
      {@render cards()}
    </div>
    <span class="sr-only">Loading title…</span>
  </div>
{:else if variant === "home"}
  <div role="status" aria-label="Loading home">
    <div aria-hidden="true">
      {#if showProviders}<div class="chips">
          <div class="bone chip-placeholder"></div>
          <div class="bone chip-placeholder"></div>
          <div class="bone chip-placeholder"></div>
        </div>{/if}
      <div class="bone heading"></div>
      {@render cards()}
      <div class="bone heading"></div>
      {@render cards()}
    </div>
    <span class="sr-only">Loading home…</span>
  </div>
{:else}
  <div role="status" aria-label="Loading titles">
    <div aria-hidden="true">{@render cards()}</div>
    <span class="sr-only">Loading titles…</span>
  </div>
{/if}

<style>
  .bone {
    background: var(--surface-2);
    border-radius: 6px;
    animation: breathe 1.6s ease-in-out infinite;
  }
  .cards {
    display: flex;
    gap: 16px;
    overflow: hidden;
    padding-bottom: 8px;
  }
  .card {
    width: 132px;
    flex: 0 0 auto;
  }
  .artwork {
    width: 100%;
    aspect-ratio: 2 / 3;
    border-radius: var(--radius);
  }
  .horizontal .card {
    width: 220px;
  }
  .horizontal .artwork {
    aspect-ratio: 16 / 9;
  }
  .card-title {
    width: 80%;
    height: 12px;
    margin-top: 10px;
  }
  .card-meta {
    width: 48%;
    height: 10px;
    margin-top: 8px;
  }
  .chips,
  .buttons {
    display: flex;
    gap: 8px;
  }
  .chip-placeholder {
    width: 92px;
    height: 34px;
    border-radius: 999px;
  }
  .chip-placeholder:nth-child(2) {
    width: 110px;
  }
  .heading {
    width: 156px;
    height: 20px;
    margin: 32px 0 16px;
  }
  .back {
    width: 40px;
    height: 40px;
    border-radius: 50%;
    margin-bottom: 24px;
  }
  .detail-head {
    display: flex;
    gap: 28px;
    align-items: center;
  }
  .detail-poster {
    width: 112px;
    aspect-ratio: 2 / 3;
    border-radius: var(--radius);
    flex-shrink: 0;
  }
  .detail-info {
    flex: 1;
    min-width: 0;
  }
  .title {
    width: min(75%, 360px);
    height: 38px;
  }
  .facts {
    width: min(55%, 230px);
    height: 13px;
    margin-top: 14px;
  }
  .tags {
    width: min(65%, 280px);
    height: 13px;
    margin-top: 10px;
  }
  .buttons {
    margin-top: 18px;
  }
  .button {
    width: 120px;
    height: 44px;
    border-radius: var(--radius-sm);
  }
  .button.secondary {
    width: 150px;
  }
  .description {
    max-width: 75ch;
    margin-top: 28px;
  }
  .line {
    width: 100%;
    height: 13px;
    margin-top: 10px;
  }
  .line.short {
    width: 72%;
  }
  @keyframes breathe {
    50% {
      opacity: 0.55;
    }
  }
  @media (max-width: 599px) {
    .detail-head {
      align-items: flex-start;
      gap: 16px;
    }
    .detail-poster {
      width: 86px;
    }
    .title {
      height: 28px;
      width: 85%;
    }
    .button {
      width: 90px;
    }
    .button.secondary {
      width: 110px;
    }
  }
  @media (min-width: 900px) {
    .card {
      width: 156px;
    }
    .detail-poster {
      width: 208px;
    }
  }
  @media (prefers-reduced-motion: reduce) {
    .bone {
      animation: none;
    }
  }
</style>
