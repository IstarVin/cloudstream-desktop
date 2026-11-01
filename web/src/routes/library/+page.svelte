<script lang="ts">
  import { createQuery, useQueryClient } from "@tanstack/svelte-query";
  import { api } from "#lib/api";
  import { Bookmark, History, Trash2, X } from "@lucide/svelte";
  import Poster from "#lib/components/Poster.svelte";
  import { toasts } from "#lib/toast.svelte";
  import { BOOKMARK_LISTS } from "#lib/types";

  let tab = $state<"history" | "bookmarks">("history");
  let clearing = $state(false);
  let clearDialog: HTMLDialogElement;
  const client = useQueryClient();
  const history = createQuery(() => ({ queryKey: ["history"], queryFn: api.history }));
  const bookmarks = createQuery(() => ({ queryKey: ["bookmarks"], queryFn: api.bookmarks }));

  async function forget(provider: string, url: string) {
    await api.deleteHistory(provider, url).catch(toasts.error);
    client.invalidateQueries({ queryKey: ["history"] });
    client.invalidateQueries({ queryKey: ["continue"] });
  }

  async function clearHistory() {
    clearing = true;
    try {
      await api.clearHistory();
      clearDialog.close();
      await Promise.all([
        client.invalidateQueries({ queryKey: ["history"] }),
        client.invalidateQueries({ queryKey: ["continue"] }),
      ]);
    } catch (error) {
      toasts.error(error);
    } finally {
      clearing = false;
    }
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
      <div class="history-actions">
        <div class="history-label">
          <h2>Recently watched</h2>
          <p>Your viewing history</p>
        </div>
        <button class="clear-trigger" onclick={() => clearDialog.showModal()} disabled={clearing}>
          <Trash2 size={16} strokeWidth={1.8} aria-hidden="true" /> Clear all
        </button>
      </div>
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

<dialog
  bind:this={clearDialog}
  class="clear-dialog"
  aria-labelledby="clear-title"
  aria-describedby="clear-description"
  oncancel={(event) => {
    if (clearing) event.preventDefault();
  }}
>
  <div class="dialog-icon"><Trash2 size={21} strokeWidth={1.8} aria-hidden="true" /></div>
  <h2 id="clear-title">Clear watch history?</h2>
  <p id="clear-description">
    This removes all titles from your history and deletes their saved watch progress. Your bookmarks
    will stay in your library.
  </p>
  <div class="dialog-actions">
    <button class="btn" onclick={() => clearDialog.close()} disabled={clearing}>Cancel</button>
    <button class="btn clear-confirm" onclick={clearHistory} disabled={clearing}>
      {clearing ? "Clearing…" : "Clear history"}
    </button>
  </div>
</dialog>

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
  .history-actions {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    margin-top: 28px;
    padding-bottom: 12px;
    border-bottom: 1px solid var(--line);
  }
  .history-label h2 {
    font-size: 18px;
  }
  .history-label p {
    margin: 3px 0 0;
    color: var(--muted);
    font-size: 13px;
  }
  .clear-trigger {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 8px;
    min-height: 44px;
    padding: 0 12px;
    border-radius: var(--radius-sm);
    color: var(--muted);
    font-size: 14px;
    font-weight: 600;
    white-space: nowrap;
  }
  .clear-trigger:hover {
    background: var(--surface-2);
    color: var(--text);
  }
  .clear-dialog {
    width: min(440px, calc(100% - 32px));
    max-width: none;
    padding: 24px;
    border: 1px solid var(--line);
    border-radius: var(--radius);
    background: var(--surface);
    color: var(--text);
    box-shadow: 0 20px 60px rgb(0 0 0 / 0.45);
  }
  .clear-dialog::backdrop {
    background: rgb(0 0 0 / 0.7);
  }
  .dialog-icon {
    display: grid;
    place-items: center;
    width: 42px;
    height: 42px;
    margin-bottom: 18px;
    border-radius: var(--radius-sm);
    color: var(--danger);
    background: rgb(255 97 112 / 0.12);
  }
  .clear-dialog h2 {
    font-size: 21px;
  }
  .clear-dialog p {
    margin: 10px 0 24px;
    color: var(--muted);
    font-size: 14px;
    line-height: 1.55;
  }
  .dialog-actions {
    display: flex;
    justify-content: flex-end;
    gap: 10px;
  }
  .clear-confirm {
    background: var(--danger);
    border-color: transparent;
    color: #260a0d;
  }
  .clear-confirm:hover:not(:disabled) {
    background: #ff7a86;
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
