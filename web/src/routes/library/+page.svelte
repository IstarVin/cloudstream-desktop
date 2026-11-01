<script lang="ts">
  import { createQuery, useQueryClient } from "@tanstack/svelte-query";
  import { api } from "#lib/api";
  import { Bookmark, History, X } from "@lucide/svelte";
  import Poster from "#lib/components/Poster.svelte";
  import { toasts } from "#lib/toast.svelte";
  import { BOOKMARK_LISTS } from "#lib/types";

  let tab = $state<"history" | "bookmarks">("history");
  const client = useQueryClient();
  const history = createQuery(() => ({ queryKey: ["history"], queryFn: api.history }));
  const bookmarks = createQuery(() => ({ queryKey: ["bookmarks"], queryFn: api.bookmarks }));

  async function forget(provider: string, url: string) {
    await api.deleteHistory(provider, url).catch(toasts.error);
    client.invalidateQueries({ queryKey: ["history"] });
    client.invalidateQueries({ queryKey: ["continue"] });
  }

  const ago = (t: number) => {
    const m = Math.round((Date.now() - t) / 60000);
    if (m < 60) return `${m} min ago`;
    if (m < 1440) return `${Math.round(m / 60)} h ago`;
    return new Date(t).toLocaleDateString();
  };
</script>

<div class="page">
  <div class="page-heading">
    <h1 class="page-title">Library</h1>
    <p>Your watchlist and the stories you’ve started.</p>
  </div>
  <div class="seg" role="group" aria-label="Library view">
    <button
      aria-pressed={tab === "history"}
      class:on={tab === "history"}
      onclick={() => (tab = "history")}
      ><History size={17} strokeWidth={1.8} aria-hidden="true" /> History</button
    >
    <button
      aria-pressed={tab === "bookmarks"}
      class:on={tab === "bookmarks"}
      onclick={() => (tab = "bookmarks")}
      ><Bookmark size={17} strokeWidth={1.8} aria-hidden="true" /> Bookmarks</button
    >
  </div>

  {#if tab === "history"}
    {#if history.data?.length}
      <div class="grid">
        {#each history.data as h (h.provider + h.url)}
          <div class="cell">
            <Poster
              provider={h.provider}
              url={h.url}
              name={h.title}
              poster={h.poster}
              meta={ago(h.lastWatched)}
              showProvider
            />
            <button
              class="forget"
              onclick={() => forget(h.provider, h.url)}
              aria-label="Remove {h.title} from history"
              ><X size={16} strokeWidth={1.8} aria-hidden="true" /></button
            >
          </div>
        {/each}
      </div>
    {:else if history.isSuccess}
      <div class="empty">Nothing watched yet.</div>
    {/if}
  {:else if bookmarks.data?.length}
    {#each BOOKMARK_LISTS as list (list)}
      {@const items = bookmarks.data.filter((b) => b.list === list)}
      {#if items.length}
        <h2 class="section-title">
          {list[0].toUpperCase() + list.slice(1)} <span class="muted">{items.length}</span>
        </h2>
        <div class="grid">
          {#each items as b (b.provider + b.url)}
            <Poster
              provider={b.provider}
              url={b.url}
              name={b.title}
              poster={b.poster}
              showProvider
            />
          {/each}
        </div>
      {/if}
    {/each}
  {:else if bookmarks.isSuccess}
    <div class="empty">No bookmarks. Use “Add to library” on a title.</div>
  {/if}
</div>

<style>
  .seg {
    display: inline-flex;
    background: var(--surface-2);
    border-radius: 999px;
    padding: 3px;
    margin-bottom: 8px;
  }
  .seg button {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    min-height: 42px;
    padding: 7px 18px;
    border-radius: 999px;
    font-weight: 600;
    color: var(--muted);
  }
  .seg button.on {
    background: var(--surface-3);
    color: var(--text);
  }
  .grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(118px, 1fr));
    gap: 16px 12px;
    margin-top: 12px;
    --card-w: 100%;
  }
  .cell {
    position: relative;
  }
  .forget {
    position: absolute;
    top: 6px;
    right: 6px;
    width: 30px;
    height: 30px;
    display: grid;
    place-items: center;
    border-radius: 50%;
    background: rgb(0 0 0 / 0.6);
    opacity: 0.85;
  }
  @media (min-width: 900px) {
    .grid {
      grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
    }
  }
</style>
