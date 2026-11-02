<script lang="ts">
  import { Captions, Check, TriangleAlert, X } from "@lucide/svelte";
  import { createQuery } from "@tanstack/svelte-query";
  import { api } from "../api";
  import { modal } from "../dialog";
  import { playTarget } from "../play-target.svelte";
  import { player } from "../player.svelte";
  import {
    AUTO_QUALITY,
    compareLinks,
    groupLinks,
    linkPriority,
    linkVisible,
    sourceRecord,
  } from "../ranking";
  import { sources } from "../sources.svelte";
  import { webPlayer } from "../web-player.svelte";
  import SourceProfiles from "./SourceProfiles.svelte";

  const settings = createQuery(() => ({
    queryKey: ["settings"],
    queryFn: api.settings,
    enabled: sources.open,
    refetchInterval: sources.open ? 3000 : false,
  }));
  const profile = $derived(
    settings.data?.source_profiles.find((p) => p.id === settings.data?.source_profile),
  );

  const t = $derived(sources.target);
  /** Playing in this browser's player, or in the server's mpv. */
  const inBrowser = $derived(!!t && webPlayer.playing(t.provider, t.data));
  const playingThis = $derived(
    !!t && player.state.provider === t.provider && player.state.data === t.data,
  );
  const currentLink = $derived(
    inBrowser ? webPlayer.current?.id : playingThis ? player.state.link?.id : null,
  );
  const failed = $derived(
    new Set(inBrowser ? webPlayer.failed : playingThis ? (player.state.failedLinks ?? []) : []),
  );
  const statusMessage = $derived(
    inBrowser
      ? webPlayer.status === "resolving"
        ? webPlayer.message
        : null
      : playingThis && player.state.status === "resolving"
        ? player.state.message
        : null,
  );
  // What auto-play would pick next: the best link that hasn't failed.
  const ranked = $derived([...sources.links].sort((a, b) => compareLinks(a, b, profile)));
  const visible = $derived(ranked.filter((l) => linkVisible(l, profile, failed, currentLink)));
  const groups = $derived(groupLinks(visible));
  const best = $derived(visible.find((l) => l.playable && !failed.has(l.id))?.id);
  let subsOpen = $state(false);

  function toggleSub(id: string) {
    sources.chosenSubs = sources.chosenSubs.includes(id)
      ? sources.chosenSubs.filter((s) => s !== id)
      : [...sources.chosenSubs, id];
    if (!sources.chosenSubs.includes(id)) return;
    const sub = sources.subtitles.find((s) => s.id === id);
    if (inBrowser && sub) void webPlayer.selectSubtitle(sub);
    else if (playingThis) player.command("sub-add", { id });
  }
</script>

{#if sources.open && t}
  <dialog
    class="sheet"
    aria-label="Sources"
    {@attach modal}
    oncancel={() => sources.hide()}
    onclick={(event) => {
      if (event.target !== event.currentTarget) return;
      const bounds = event.currentTarget.getBoundingClientRect();
      if (
        event.clientX < bounds.left ||
        event.clientX > bounds.right ||
        event.clientY < bounds.top ||
        event.clientY > bounds.bottom
      )
        sources.hide();
    }}
  >
    <div class="grab"></div>
    <header>
      <div class="head-text">
        <h2>Sources</h2>
        <div class="muted ellipsis">
          {t.title}{t.label ? ` · ${t.label}` : ""}
          {playTarget.remote ? ` · on ${playTarget.label}` : ""}
        </div>
      </div>
      <button class="close" onclick={() => sources.hide()} aria-label="Close"
        ><X size={22} strokeWidth={1.8} aria-hidden="true" /></button
      >
    </header>
    <div class="list">
      <div class="profile-controls">
        {#if settings.data}
          <SourceProfiles
            settings={settings.data}
            knownSources={sources.links.map((l) => l.source)}
          />
        {:else if settings.isError}
          <p class="err">
            Could not load source profiles. <button class="btn" onclick={() => settings.refetch()}
              >Retry</button
            >
          </p>
        {:else}<p class="muted">Loading source profiles…</p>{/if}
      </div>
      <div class="status">
        {#if !sources.done}
          <div class="spinner"></div>
          <span>Finding links… {sources.links.length} so far</span>
        {:else if sources.error && !sources.links.length}
          <TriangleAlert size={18} strokeWidth={1.8} aria-hidden="true" /><span class="err"
            >{sources.error}</span
          >
        {:else}
          <span>{visible.length} links from {groups.length} sources</span>
        {/if}
        {#if visible.length < sources.links.length}<span
            >{sources.links.length - visible.length} hidden by profile</span
          >{/if}
        {#if statusMessage}
          <span class="accent">· {statusMessage}</span>
        {/if}
      </div>

      {#if sources.done && sources.links.length && !visible.length}
        <p class="empty">
          This profile hides every link. Choose another profile or edit its visibility settings.
        </p>
      {/if}
      {#each groups as g (g.source)}
        <div class="group">
          <div class="group-name">
            <span>{g.source}</span>
            <span class={["record", { good: g.links[0].score > 0.6, poor: g.links[0].score < 0.4 }]}
              >{sourceRecord(g.links[0])}</span
            >
          </div>
          {#each g.links as l (l.id)}
            {@const detailAt = l.name.indexOf(" · ")}
            <div class="link-row">
              <button
                class={[
                  "link",
                  {
                    current: l.id === currentLink,
                    failed: failed.has(l.id) && l.id !== currentLink,
                  },
                ]}
                disabled={!l.playable}
                onclick={() => sources.pick(l, playingThis)}
              >
                <span class="lname">
                  <span class="row-top">
                    <span class={["q", { hd: l.quality >= 720 && l.quality !== AUTO_QUALITY }]}
                      >{l.qualityLabel}</span
                    >
                    <span class="type">{l.type} · priority {linkPriority(l, profile)}</span>
                    {#if failed.has(l.id) && l.id !== currentLink}
                      <span class="tag bad">Failed</span>
                    {:else if l.id === currentLink}
                      <span class="tag selected"
                        ><Check size={14} strokeWidth={2} aria-hidden="true" /> Playing</span
                      >
                    {:else if l.id === best}
                      <span class="tag">Best</span>
                    {/if}
                  </span>
                  <span class="link-name"
                    >{detailAt === -1 ? l.name : l.name.slice(0, detailAt)}</span
                  >
                  {#if detailAt !== -1}
                    <span class="details">{l.name.slice(detailAt + 3)}</span>
                  {/if}
                  <span class="host"
                    >{l.host}{l.proxied ? " · proxied" : ""}{l.drm
                      ? l.playable
                        ? " · ClearKey"
                        : " · DRM (unsupported)"
                      : ""}</span
                  >
                </span>
              </button>
            </div>
          {/each}
        </div>
      {/each}
    </div>

    {#if sources.subtitles.length}
      <div class="subs">
        <button class="subs-head" onclick={() => (subsOpen = !subsOpen)}>
          <Captions size={18} strokeWidth={1.8} aria-hidden="true" />
          <span>Subtitles ({sources.subtitles.length})</span>
          <span class="muted"
            >{sources.chosenSubs.length
              ? `${sources.chosenSubs.length} chosen`
              : "English added automatically"}</span
          >
        </button>
        {#if subsOpen}
          <div class="sub-list">
            {#each sources.subtitles as s (s.id)}
              <button
                class={["chip", { on: sources.chosenSubs.includes(s.id) }]}
                onclick={() => toggleSub(s.id)}>{s.lang}</button
              >
            {/each}
          </div>
        {/if}
      </div>
    {/if}
  </dialog>
{/if}

<style>
  .sheet::backdrop {
    background: var(--overlay);
    animation: scrim-in 200ms ease-out both;
  }
  .sheet {
    position: fixed;
    left: 0;
    right: 0;
    bottom: 0;
    top: auto;
    width: 100%;
    max-width: none;
    margin: 0;
    padding: 0;
    color: var(--text);
    max-height: 86dvh;
    z-index: 61;
    background: var(--surface);
    border: 0;
    border-top: 1px solid var(--line);
    border-radius: 18px 18px 0 0;
    display: flex;
    flex-direction: column;
    padding-bottom: var(--safe-b);
    animation: up 300ms cubic-bezier(0.16, 1, 0.3, 1) both;
  }
  @keyframes scrim-in {
    from {
      opacity: 0;
    }
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
    width: 44px;
    height: 44px;
    flex-shrink: 0;
    display: grid;
    place-items: center;
    border-radius: 50%;
    transition:
      background-color 150ms ease,
      transform 150ms ease;
  }
  .close:hover {
    background: var(--surface-2);
  }
  .close:active {
    transform: scale(0.92);
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
  .profile-controls {
    padding: 10px 8px 12px;
  }
  .empty {
    padding: 12px 8px;
    color: var(--muted);
  }
  .group-name {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 8px;
    font-size: 12px;
    font-weight: 700;
    text-transform: uppercase;
    letter-spacing: 0.06em;
    color: var(--faint);
    padding: 20px 8px 8px;
  }
  .record {
    font-weight: 500;
    text-transform: none;
    letter-spacing: 0;
    font-size: 12px;
  }
  .record.good {
    color: var(--good);
  }
  .record.poor {
    color: var(--danger);
  }
  .tag {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    flex-shrink: 0;
    font-size: 11px;
    font-weight: 700;
    padding: 2px 7px;
    border-radius: 999px;
    background: var(--accent-soft);
    color: var(--accent);
  }
  .tag.selected {
    margin-left: auto;
  }
  .tag.bad {
    background: transparent;
    color: var(--danger);
    border: 1px solid currentColor;
  }
  .link.failed .lname {
    opacity: 0.6;
  }
  .link-row {
    display: flex;
    gap: 4px;
    margin-bottom: 4px;
  }
  .link-row .link {
    margin-bottom: 0;
  }
  .link {
    width: 100%;
    display: block;
    padding: 12px;
    margin-bottom: 4px;
    border-radius: var(--radius-sm);
    text-align: left;
    min-height: 52px;
    background: var(--surface-2);
    transition:
      background-color 150ms ease,
      color 150ms ease;
  }
  .link:hover:not(:disabled) {
    background: var(--surface-3);
  }
  .link.current {
    background: var(--accent-soft);
    color: var(--text);
  }
  .link:disabled {
    opacity: 0.4;
    cursor: default;
  }
  .q {
    flex-shrink: 0;
    min-width: 46px;
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
    gap: 3px;
  }
  .row-top {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 4px;
  }
  .row-top .tag:last-child {
    margin-left: auto;
  }
  .link-name {
    font-weight: 600;
    line-height: 1.35;
  }
  .link-name,
  .details {
    overflow-wrap: anywhere;
  }
  .details {
    font-size: 12.5px;
    color: var(--muted);
  }
  .host {
    font-size: 12px;
    color: var(--faint);
    overflow-wrap: anywhere;
  }
  .type {
    flex-shrink: 0;
    font-size: 11.5px;
    color: var(--faint);
    text-transform: uppercase;
    letter-spacing: 0.04em;
  }
  .link :global(svg) {
    flex-shrink: 0;
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
      height: calc(100dvh - 32px);
      max-height: none;
      border-radius: 18px;
      border: 1px solid var(--line);
      animation-name: side-in;
    }
    .grab {
      display: none;
    }
  }
  @keyframes side-in {
    from {
      opacity: 0;
      transform: translateX(30px);
    }
  }
  @media (prefers-reduced-motion: reduce) {
    .sheet,
    .sheet::backdrop {
      animation: none;
    }
    .close:active {
      transform: none;
    }
  }
</style>
