<script lang="ts">
  import {
    Check,
    ChevronDown,
    Layers,
    Search,
    X,
    Sparkles,
    Film,
    Tv,
    Drama,
    Smile,
    Clapperboard,
    Radio,
    Download,
    Shield,
    Ellipsis,
  } from "@lucide/svelte";
  import { CONTENT_TYPES, contentTypeLabels, matchesContentType } from "../content-types";
  import type { Provider } from "../types";

  let {
    providers,
    current,
    onchoose,
  }: {
    providers: Provider[];
    current: Provider | undefined;
    onchoose: (name: string) => void;
  } = $props();

  const categoryIcons = {
    anime: Sparkles,
    movies: Film,
    series: Tv,
    drama: Drama,
    cartoons: Smile,
    documentaries: Clapperboard,
    live: Radio,
    torrent: Download,
    adult: Shield,
    other: Ellipsis,
  };

  const id = $props.id();
  let open = $state(false);
  let query = $state("");
  let contentTypes = $state<string[]>([]);
  let trigger: HTMLButtonElement;
  const filters = $derived(
    CONTENT_TYPES.filter((group) => providers.some((p) => matchesContentType(p.types, group.id))),
  );
  const matching = $derived(
    providers.filter(
      (p) => !contentTypes.length || contentTypes.some((type) => matchesContentType(p.types, type)),
    ),
  );
  const selectedLabels = $derived(
    CONTENT_TYPES.filter((group) => contentTypes.includes(group.id))
      .map((group) => group.label)
      .join(", "),
  );
  const visible = $derived(
    matching
      .filter((p) =>
        `${p.name} ${p.plugin} ${language(p.lang)}`
          .toLocaleLowerCase()
          .includes(query.trim().toLocaleLowerCase()),
      )
      .toSorted((a, b) => a.name.localeCompare(b.name)),
  );

  const languageNames = new Intl.DisplayNames(["en"], { type: "language" });
  function language(code: string) {
    if (!code) return "Unknown language";
    try {
      return languageNames.of(code) ?? code;
    } catch {
      return code;
    }
  }

  function close() {
    open = false;
    trigger?.focus();
  }

  function choose(name: string) {
    onchoose(name);
    close();
  }

  function readFilters(): string[] {
    try {
      const saved: unknown = JSON.parse(localStorage.getItem("home-provider-categories") ?? "[]");
      if (!Array.isArray(saved)) return [];
      return CONTENT_TYPES.filter((group) => saved.includes(group.id)).map((group) => group.id);
    } catch {
      return contentTypes;
    }
  }

  function setFilters(types: string[]) {
    contentTypes = types;
    try {
      localStorage.setItem("home-provider-categories", JSON.stringify(types));
    } catch {
      // Storage may be unavailable; filtering still works for this session.
    }
  }

  function toggleFilter(type: string) {
    setFilters(
      contentTypes.includes(type)
        ? contentTypes.filter((selected) => selected !== type)
        : [...contentTypes, type],
    );
  }

  function toggle() {
    if (!open) {
      query = "";
      contentTypes = readFilters();
    }
    open = !open;
  }
</script>

<svelte:window
  onkeydown={(e) => {
    if (open && e.key === "Escape" && !e.defaultPrevented) {
      e.preventDefault();
      close();
    }
  }}
/>

<section class="provider-picker" aria-label="Source selection">
  <div class="toolbar">
    <button
      class="source-trigger"
      bind:this={trigger}
      aria-expanded={open}
      aria-controls="{id}-sources"
      onclick={toggle}
    >
      <span class="source-icon" aria-hidden="true">
        <Layers size={22} strokeWidth={1.8} />
        {#if current?.iconUrl}<img
            src={current.iconUrl}
            alt=""
            referrerpolicy="no-referrer"
            onerror={(e) => e.currentTarget.setAttribute("hidden", "")}
            onload={(e) => e.currentTarget.removeAttribute("hidden")}
          />{/if}
      </span>
      <span class="source-heading">
        <span class="source-name">{current?.name ?? "Choose a source"}</span>
        <span class="source-hint"
          >{current
            ? `${language(current.lang)} · Change source`
            : "Browse installed plugins"}</span
        >
      </span>
      <ChevronDown
        size={18}
        strokeWidth={1.8}
        class={open ? "chevron expanded" : "chevron"}
        aria-hidden="true"
      />
    </button>
    <a class="manage" href="/settings">Manage plugins</a>
  </div>

  {#if open}
    <div class="source-browser" id="{id}-sources" role="region" aria-label="Available sources">
      <div class="search-box">
        <Search size={18} strokeWidth={1.8} aria-hidden="true" />
        <input
          type="search"
          placeholder="Find a source or plugin…"
          aria-label="Find a source or plugin"
          bind:value={query}
          {@attach (input) => input.focus()}
        />
        <button class="close" aria-label="Close source picker" onclick={close}
          ><X size={18} strokeWidth={1.8} aria-hidden="true" /></button
        >
      </div>
      <div class="filters" role="group" aria-label="Filter sources by content type">
        {#each filters as filter (filter.id)}
          {@const Icon = categoryIcons[filter.id]}
          <button
            class={["chip", { on: contentTypes.includes(filter.id) }]}
            aria-pressed={contentTypes.includes(filter.id)}
            onclick={() => toggleFilter(filter.id)}
          >
            <Icon size={16} strokeWidth={1.8} aria-hidden="true" />
            <span>{filter.label}</span>
          </button>
        {/each}
      </div>
      <div class="list-heading" role="status">
        {visible.length}
        {visible.length === 1 ? "source" : "sources"}{selectedLabels
          ? ` for ${selectedLabels}`
          : " available"}
      </div>
      {#if visible.length}
        <ul class="source-list">
          {#each visible as provider (provider.name)}
            <li>
              <button
                class={["source-option", { selected: provider.name === current?.name }]}
                aria-pressed={provider.name === current?.name}
                onclick={() => choose(provider.name)}
              >
                <span class="source-icon small" aria-hidden="true">
                  <Layers size={19} strokeWidth={1.8} />
                  {#if provider.iconUrl}<img
                      src={provider.iconUrl}
                      alt=""
                      loading="lazy"
                      referrerpolicy="no-referrer"
                      onerror={(e) => e.currentTarget.setAttribute("hidden", "")}
                      onload={(e) => e.currentTarget.removeAttribute("hidden")}
                    />{/if}
                </span>
                <span class="option-text">
                  <span class="option-name">{provider.name}</span>
                  <span class="option-meta"
                    >{language(provider.lang)} · {contentTypeLabels(provider.types)}</span
                  >
                </span>
                {#if provider.name === current?.name}<Check
                    size={18}
                    strokeWidth={2}
                    aria-hidden="true"
                  /><span class="sr-only">Selected</span>{/if}
              </button>
            </li>
          {/each}
        </ul>
      {:else}
        <div class="no-sources">
          <p>No sources match{query.trim() ? ` “${query.trim()}”` : " these categories"}.</p>
          <button
            class="btn"
            onclick={() => {
              query = "";
              setFilters([]);
            }}>Reset filters</button
          >
        </div>
      {/if}
    </div>
  {/if}
</section>

<style>
  .provider-picker {
    margin-bottom: 28px;
  }
  .toolbar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
  }
  .source-trigger {
    display: flex;
    align-items: center;
    gap: 12px;
    min-width: 0;
    width: 360px;
    max-width: 100%;
    padding: 10px 12px;
    text-align: left;
    background: var(--surface-2);
    border: 1px solid transparent;
    border-radius: var(--radius);
  }
  .source-trigger:hover,
  .source-trigger[aria-expanded="true"] {
    background: var(--surface-2);
    border-color: var(--muted);
  }
  .source-icon {
    position: relative;
    display: grid;
    place-items: center;
    flex: 0 0 40px;
    height: 40px;
    color: var(--muted);
    background: var(--surface-2);
    border-radius: var(--radius-sm);
    overflow: hidden;
  }
  .source-icon img {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    padding: 7px;
    object-fit: contain;
    background: var(--surface-2);
  }
  .source-heading,
  .option-text {
    display: flex;
    flex-direction: column;
    min-width: 0;
    flex: 1;
  }
  .source-name {
    font-size: 16px;
    font-weight: 650;
    overflow-wrap: anywhere;
  }
  .source-hint {
    color: var(--muted);
    font-size: 12px;
  }
  .source-trigger :global(.chevron) {
    color: var(--muted);
    transition: transform 180ms cubic-bezier(0.16, 1, 0.3, 1);
  }
  .source-trigger :global(.expanded) {
    transform: rotate(180deg);
  }
  .manage {
    color: var(--muted);
    font-size: 13px;
    white-space: nowrap;
    padding-block: 12px;
  }
  .manage:hover {
    color: var(--text);
    text-decoration: underline;
    text-underline-offset: 4px;
  }
  .filters {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-top: 16px;
  }
  .filters .chip {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 8px;
    min-height: 44px;
    padding: 0 15px;
    line-height: 1;
    font-weight: 550;
    transition:
      background-color 160ms,
      border-color 160ms,
      color 160ms;
  }
  .filters .chip span {
    display: block;
    line-height: 20px;
  }
  .filters .chip :global(svg) {
    display: block;
    flex: 0 0 16px;
  }
  .filters .chip:hover:not(.on) {
    border-color: var(--muted);
  }
  .source-browser {
    margin-top: 16px;
    padding: 16px;
    border: 1px solid var(--line);
    border-radius: var(--radius);
    background: var(--surface);
  }
  .search-box {
    display: flex;
    align-items: center;
    gap: 10px;
    color: var(--muted);
    border: 1px solid var(--line);
    background: var(--bg);
    border-radius: var(--radius-sm);
    padding-left: 12px;
  }
  .search-box:focus-within {
    border-color: var(--accent);
  }
  .search-box input {
    flex: 1;
    width: 100%;
    min-height: 44px;
    padding: 8px 0;
    background: none;
    border: 0;
    border-radius: 0;
    font-size: 16px;
  }
  .search-box input:focus-visible {
    outline: none;
  }
  .close {
    display: grid;
    place-items: center;
    flex: 0 0 44px;
    height: 44px;
    border-radius: var(--radius-sm);
  }
  .close:hover {
    color: var(--text);
    background: var(--surface-2);
  }
  .list-heading {
    margin: 14px 0 8px;
    color: var(--muted);
    font-size: 12px;
  }
  .source-list {
    list-style: none;
    margin: 0;
    padding: 2px;
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 4px 12px;
    max-height: 360px;
    overflow-y: auto;
    overscroll-behavior: contain;
  }
  .source-list li {
    min-width: 0;
  }
  .source-option {
    display: flex;
    align-items: center;
    gap: 10px;
    width: 100%;
    min-height: 68px;
    padding: 10px;
    border-radius: var(--radius-sm);
    text-align: left;
  }
  .source-option:hover {
    background: var(--surface-2);
  }
  .source-option.selected {
    background: var(--accent-soft);
    color: var(--accent);
  }
  .source-icon.small {
    flex-basis: 34px;
    height: 34px;
  }
  .option-name {
    font-size: 14px;
    font-weight: 600;
    overflow-wrap: anywhere;
  }
  .option-meta {
    margin-top: 2px;
    color: var(--muted);
    font-size: 12px;
    overflow-wrap: anywhere;
  }
  .selected .option-meta {
    color: var(--text);
  }
  .no-sources {
    padding: 20px 12px;
    text-align: center;
    color: var(--muted);
  }
  .no-sources p {
    margin: 0 0 16px;
    overflow-wrap: anywhere;
  }
  @media (prefers-reduced-motion: reduce) {
    .filters .chip,
    .source-trigger :global(.chevron) {
      transition: none;
    }
  }
  @media (max-width: 599px) {
    .toolbar {
      align-items: stretch;
      flex-direction: column;
      gap: 0;
    }
    .source-trigger {
      width: 100%;
    }
    .manage {
      align-self: flex-end;
      padding-block: 8px;
    }
    .filters {
      gap: 6px;
    }
    .filters .chip {
      padding-inline: 12px;
    }
    .source-browser {
      padding: 12px;
    }
    .source-list {
      grid-template-columns: minmax(0, 1fr);
      max-height: 320px;
    }
  }
</style>
