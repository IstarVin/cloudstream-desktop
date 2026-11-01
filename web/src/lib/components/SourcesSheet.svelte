<script lang="ts">
  import { player } from "../player.svelte";
  import { sources } from "../sources.svelte";
  import Icon from "./Icon.svelte";

  const t = $derived(sources.target);
  const playingThis = $derived(!!t && player.state.provider === t.provider && player.state.data === t.data);
  const currentLink = $derived(playingThis ? player.state.link?.id : null);
  let subsOpen = $state(false);

  function toggleSub(id: string) {
    sources.chosenSubs = sources.chosenSubs.includes(id)
      ? sources.chosenSubs.filter((s) => s !== id)
      : [...sources.chosenSubs, id];
    if (playingThis && sources.chosenSubs.includes(id)) player.command("sub-add", { id });
  }
</script>

{#if sources.open && t}
  <div class="scrim" onclick={() => sources.hide()} role="presentation"></div>
  <div class="sheet" role="dialog" aria-label="Sources">
    <div class="grab"></div>
    <header>
      <div class="head-text">
        <h2>Sources</h2>
        <div class="muted ellipsis">{t.title}{t.label ? ` · ${t.label}` : ""}</div>
      </div>
      <button class="close" onclick={() => sources.hide()} aria-label="Close"><Icon name="close" /></button>
    </header>

    <div class="status">
      {#if !sources.done}
        <div class="spinner"></div>
        <span>Finding links… {sources.links.length} so far</span>
      {:else if sources.error && !sources.links.length}
        <Icon name="alert" size={18} /><span class="err">{sources.error}</span>
      {:else}
        <span>{sources.links.length} links from {sources.groups.length} sources</span>
      {/if}
      {#if playingThis && player.state.status === "resolving"}
        <span class="accent">· {player.state.message}</span>
      {/if}
    </div>

    <div class="list">
      {#each sources.groups as g (g.source)}
        <div class="group">
          <div class="group-name">{g.source}</div>
          {#each g.links as l (l.id)}
            <button
              class="link"
              class:current={l.id === currentLink}
              disabled={!l.playable}
              onclick={() => sources.pick(l, playingThis)}
            >
              <span class="q" class:hd={l.quality >= 720 && l.quality !== 400}>{l.qualityLabel}</span>
              <span class="lname">
                <span class="ellipsis">{l.name}</span>
                <span class="host">{l.host}{l.proxied ? " · proxied" : ""}{l.drm ? " · DRM (unsupported)" : ""}</span>
              </span>
              <span class="type">{l.type}</span>
              {#if l.id === currentLink}<Icon name="check" size={18} />{/if}
            </button>
          {/each}
        </div>
      {/each}
    </div>

    {#if sources.subtitles.length}
      <div class="subs">
        <button class="subs-head" onclick={() => (subsOpen = !subsOpen)}>
          <Icon name="subtitles" size={18} />
          <span>Subtitles ({sources.subtitles.length})</span>
          <span class="muted">{sources.chosenSubs.length ? `${sources.chosenSubs.length} chosen` : "English added automatically"}</span>
        </button>
        {#if subsOpen}
          <div class="sub-list">
            {#each sources.subtitles as s (s.id)}
              <button class="chip" class:on={sources.chosenSubs.includes(s.id)} onclick={() => toggleSub(s.id)}>{s.lang}</button>
            {/each}
          </div>
        {/if}
      </div>
    {/if}
  </div>
{/if}

<style>
  .scrim {
    position: fixed;
    inset: 0;
    background: rgb(0 0 0 / 0.55);
    z-index: 60;
  }
  .sheet {
    position: fixed;
    left: 0;
    right: 0;
    bottom: 0;
    max-height: 86vh;
    z-index: 61;
    background: var(--surface);
    border-top: 1px solid var(--line);
    border-radius: 18px 18px 0 0;
    display: flex;
    flex-direction: column;
    padding-bottom: var(--safe-b);
    animation: up 0.2s ease-out;
  }
  @keyframes up {
    from {
      transform: translateY(30px);
      opacity: 0;
    }
  }
  .grab {
    width: 40px;
    height: 4px;
    border-radius: 2px;
    background: var(--surface-3);
    margin: 8px auto 0;
  }
  header {
    display: flex;
    align-items: flex-start;
    gap: 12px;
    padding: 10px 16px 6px;
  }
  .head-text {
    flex: 1;
    min-width: 0;
  }
  h2 {
    font-size: 19px;
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
  .ellipsis {
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .status {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 4px 16px 10px;
    font-size: 13.5px;
    color: var(--muted);
    flex-wrap: wrap;
  }
  .err {
    color: var(--danger);
  }
  .accent {
    color: var(--accent);
  }
  .list {
    overflow-y: auto;
    padding: 0 8px 8px;
    flex: 1;
  }
  .group-name {
    font-size: 12px;
    font-weight: 700;
    text-transform: uppercase;
    letter-spacing: 0.06em;
    color: var(--faint);
    padding: 12px 8px 4px;
  }
  .link {
    width: 100%;
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 10px 8px;
    border-radius: var(--radius-sm);
    text-align: left;
    min-height: 52px;
  }
  .link:hover:not(:disabled) {
    background: var(--surface-2);
  }
  .link.current {
    background: var(--accent-soft);
    color: var(--accent);
  }
  .link:disabled {
    opacity: 0.4;
    cursor: default;
  }
  .q {
    min-width: 52px;
    text-align: center;
    font-size: 12.5px;
    font-weight: 700;
    padding: 3px 6px;
    border-radius: 6px;
    background: var(--surface-3);
    color: var(--muted);
  }
  .q.hd {
    background: var(--accent-soft);
    color: var(--accent);
  }
  .lname {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
  }
  .host {
    font-size: 12px;
    color: var(--faint);
  }
  .type {
    font-size: 11.5px;
    color: var(--faint);
  }
  .subs {
    border-top: 1px solid var(--line);
    padding: 6px 16px 12px;
  }
  .subs-head {
    display: flex;
    align-items: center;
    gap: 8px;
    width: 100%;
    padding: 8px 0;
    font-weight: 600;
  }
  .subs-head .muted {
    margin-left: auto;
    font-weight: 400;
    font-size: 13px;
  }
  .sub-list {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    max-height: 160px;
    overflow-y: auto;
  }
  @media (min-width: 900px) {
    .sheet {
      left: auto;
      right: 16px;
      bottom: 16px;
      top: 16px;
      width: 440px;
      max-height: none;
      border-radius: 18px;
      border: 1px solid var(--line);
    }
    .grab {
      display: none;
    }
  }
</style>
