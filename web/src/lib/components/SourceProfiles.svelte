<script lang="ts">
  import { Minus, Plus, RotateCcw, Pencil, Check, Search, X, ChevronDown } from "@lucide/svelte";
  import { useQueryClient } from "@tanstack/svelte-query";
  import { api } from "../api";
  import { QUALITIES } from "../ranking";
  import { toasts } from "../toast.svelte";
  import type { Settings, SourceProfile } from "../types";

  let {
    settings,
    knownSources = [],
    showPreview = true,
  }: { settings: Settings; knownSources?: string[]; showPreview?: boolean } = $props();
  const client = useQueryClient();
  const active = $derived(settings.source_profiles.find((p) => p.id === settings.source_profile));
  let draft = $state<SourceProfile | null>(null);
  let saving = $state(false);
  let error = $state<string | null>(null);
  let sourceName = $state("");
  let search = $state("");
  let addedNames = $state<string[]>([]);
  let announcement = $state("");
  let resetBackup = $state<SourceProfile | null>(null);
  let previewSource = $state("");
  let previewQuality = $state(1080);
  const shown = $derived(draft ?? active);
  const names = $derived(
    [
      ...new Set([
        ...knownSources,
        ...addedNames,
        ...settings.source_profiles.flatMap((profile) => Object.keys(profile.sourcePriorities)),
        ...Object.keys(shown?.sourcePriorities ?? {}),
      ]),
    ].sort(),
  );

  const filteredNames = $derived(
    names.filter((name) => name.toLowerCase().includes(search.trim().toLowerCase())),
  );
  const previewSourcePriority = $derived(shown?.sourcePriorities[previewSource] ?? 1);
  const previewQualityPriority = $derived(
    shown?.qualityPriorities[previewQuality] ??
      QUALITIES.find((q) => q.value === previewQuality)?.priority ??
      0,
  );
  const previewTotal = $derived(previewSourcePriority + previewQualityPriority);
  const changeCount = $derived.by(() => {
    if (!draft || !active) return 0;
    const sources = new Set([
      ...Object.keys(active.sourcePriorities),
      ...Object.keys(draft.sourcePriorities),
    ]);
    return (
      Number(draft.name.trim() !== active.name) +
      Number(draft.hideFailedSources !== active.hideFailedSources) +
      Number(draft.hideNegativeSources !== active.hideNegativeSources) +
      [...sources].filter((name) => draft!.sourcePriorities[name] !== active.sourcePriorities[name])
        .length +
      QUALITIES.filter(
        (q) =>
          (draft!.qualityPriorities[q.value] ?? q.priority) !==
          (active.qualityPriorities[q.value] ?? q.priority),
      ).length
    );
  });

  function resetProfile() {
    if (!draft) return;
    resetBackup = structuredClone($state.snapshot(draft));
    draft.sourcePriorities = {};
    draft.qualityPriorities = {};
    draft.hideFailedSources = false;
    draft.hideNegativeSources = false;
    announcement = "Priorities and visibility reset. Save to apply, or undo the reset.";
  }

  function edit() {
    if (!active) return;
    draft = {
      ...active,
      sourcePriorities: { ...active.sourcePriorities },
      qualityPriorities: { ...active.qualityPriorities },
    };
    sourceName = "";
    resetBackup = null;
    announcement = "";
    error = null;
  }

  async function save(patch: Partial<Settings>) {
    saving = true;
    error = null;
    try {
      client.setQueryData(["settings"], await api.saveSettings(patch));
      draft = null;
      return true;
    } catch (e) {
      error = e instanceof Error ? e.message : String(e);
      return false;
    } finally {
      saving = false;
    }
  }

  async function saveProfile(event: SubmitEvent) {
    event.preventDefault();
    if (!draft || saving || !changeCount) return;
    const profile = { ...draft, name: draft.name.trim() || `Profile ${draft.id}` };
    if (
      await save({
        source_profiles: settings.source_profiles.map((p) => (p.id === profile.id ? profile : p)),
      })
    ) {
      toasts.show("Source profile saved");
    }
  }

  function addSource() {
    const name = sourceName.trim();
    if (!draft || !name) return;
    const exists = draft.sourcePriorities[name] !== undefined;
    draft.sourcePriorities[name] ??= 1;
    if (!addedNames.includes(name)) addedNames = [...addedNames, name];
    search = "";
    resetBackup = null;
    announcement = exists
      ? `${name} is already in this profile.`
      : `Added ${name} with priority 1.`;
    sourceName = "";
  }
</script>

{#snippet priorityControl(label: string, value: number, update: (value: number) => void)}
  {#if draft}
    <div class="stepper">
      <button
        type="button"
        aria-label="Lower priority for {label}"
        disabled={value <= -1000}
        onclick={() => {
          resetBackup = null;
          update(Math.max(-1000, value - 1));
        }}><Minus size={16} /></button
      >
      <input
        type="number"
        min="-1000"
        max="1000"
        step="1"
        required
        aria-label="Priority for {label}"
        {value}
        oninput={(event) => {
          resetBackup = null;
          if (event.currentTarget.validity.valid) update(Number(event.currentTarget.value));
        }}
      />
      <button
        type="button"
        aria-label="Raise priority for {label}"
        disabled={value >= 1000}
        onclick={() => {
          resetBackup = null;
          update(Math.min(1000, value + 1));
        }}><Plus size={16} /></button
      >
    </div>
  {:else}
    <span class="score">{value}</span>
  {/if}
{/snippet}

<div class={["profiles", { "in-sources": !showPreview }]}>
  <div class="profile-picker">
    <label class="stack">
      <span>Active source profile</span>
      <select
        value={settings.source_profile}
        disabled={saving || draft !== null}
        onchange={(e) => save({ source_profile: Number(e.currentTarget.value) })}
      >
        {#each settings.source_profiles as profile (profile.id)}
          <option value={profile.id}>{profile.name}</option>
        {/each}
      </select>
    </label>
    {#if !draft}
      <button class="btn" onclick={edit} disabled={saving || !active}
        ><Pencil size={16} /> Edit profile</button
      >
    {:else}
      <span class="editing-status">{changeCount ? "Unsaved changes" : "Editing profile"}</span>
    {/if}
  </div>
  <p class="hint">
    Applies to auto-play, fallback and source pickers. Changing profiles keeps the current stream
    playing.
  </p>

  {#if shown && (showPreview || draft)}
    {#if showPreview}
      <details class="priority-guide">
        <summary
          ><span
            ><strong>Higher priorities play first</strong><small
              >Source + quality = video priority. Preview a combination.</small
            ></span
          ><ChevronDown size={18} /></summary
        >
        <div class="preview-controls">
          <label class="stack"
            >Source
            <select bind:value={previewSource}>
              <option value="">Unlisted source (default)</option>
              {#each names as name (name)}<option value={name}>{name}</option>{/each}
            </select>
          </label>
          <label class="stack"
            >Quality
            <select bind:value={previewQuality}>
              {#each QUALITIES as quality (quality.value)}<option value={quality.value}
                  >{quality.label}</option
                >{/each}
            </select>
          </label>
        </div>
        <div class="preview-result" aria-live="polite">
          <strong>{previewSourcePriority} + {previewQualityPriority} = {previewTotal}</strong>
          <span
            >{previewTotal < 0 && shown.hideNegativeSources
              ? "Hidden and skipped in auto-play"
              : previewTotal >= 10
                ? "Starts auto-play immediately"
                : "Uses your usual wait settings"}</span
          >
        </div>
        <p class="hint">
          A total of 10 or more starts auto-play immediately. This preview uses {draft
            ? "your unsaved changes"
            : "the selected profile"}.
        </p>
      </details>
    {/if}
    <form onsubmit={saveProfile}>
      <fieldset
        disabled={saving}
        oninput={() => {
          resetBackup = null;
        }}
      >
        {#if draft}
          <label class="stack profile-name"
            >Profile name
            <input bind:value={draft.name} maxlength="80" placeholder="Profile {draft.id}" />
          </label>
        {/if}

        <div class="priority-columns">
          <section aria-labelledby="source-priorities-title">
            <div class="group-heading">
              <h3 id="source-priorities-title">Source priorities</h3>
              <span class="column-label">Higher = preferred</span>
            </div>
            <p class="hint">Unlisted sources use 1. Names must match the source labels exactly.</p>
            {#if names.length > 5 || search}
              <div class="source-search">
                <Search size={16} />
                <input
                  type="search"
                  aria-label="Find a source"
                  placeholder="Find a source…"
                  bind:value={search}
                />
                {#if search}<button
                    type="button"
                    class="icon-button"
                    aria-label="Clear source search"
                    onclick={() => {
                      search = "";
                    }}><X size={16} /></button
                  >{/if}
              </div>
              <p class="search-count">{filteredNames.length} of {names.length} sources</p>
            {/if}
            <div class="priority-list">
              {#each filteredNames as name (name)}
                <div
                  class={[
                    "priority-row source-row",
                    {
                      changed:
                        draft !== null &&
                        draft.sourcePriorities[name] !== active?.sourcePriorities[name],
                    },
                  ]}
                >
                  <div class="source-label">
                    <span>{name}</span>
                    <small
                      >{draft && draft.sourcePriorities[name] !== active?.sourcePriorities[name]
                        ? "Modified"
                        : shown.sourcePriorities[name] === undefined
                          ? "Default · 1"
                          : "Custom"}</small
                    >
                  </div>
                  {@render priorityControl(name, shown.sourcePriorities[name] ?? 1, (value) => {
                    if (draft) draft.sourcePriorities[name] = value;
                  })}
                  {#if draft}
                    <button
                      class="icon-button"
                      type="button"
                      aria-label="Reset priority for {name}"
                      title="Reset to default (1)"
                      disabled={draft.sourcePriorities[name] === undefined}
                      onclick={() => {
                        if (draft) delete draft.sourcePriorities[name];
                        resetBackup = null;
                      }}
                    >
                      <RotateCcw size={15} />
                    </button>
                  {/if}
                </div>
              {:else}
                {#if search}
                  <div class="empty-sources">
                    <strong>No matching sources</strong>
                    <p class="hint">Try a different name or clear the search.</p>
                    <button
                      type="button"
                      class="reset"
                      onclick={() => {
                        search = "";
                      }}>Clear search</button
                    >
                  </div>
                {:else}
                  <div class="empty-sources">
                    <strong>All sources use the default priority</strong>
                    <p class="hint">
                      Open an episode’s sources to see its names here, or add an exact source name{draft
                        ? " below"
                        : " when editing"}.
                    </p>
                    <span class="default-value">Default priority <b>1</b></span>
                  </div>
                {/if}
              {/each}
            </div>
            {#if draft}
              <label class="stack add-label" for="priority-source-name">Add a source</label>
              <div class="add-source">
                <input
                  id="priority-source-name"
                  bind:value={sourceName}
                  maxlength="200"
                  placeholder="Exact source name"
                  onkeydown={(e) => {
                    if (e.key === "Enter") {
                      e.preventDefault();
                      addSource();
                    }
                  }}
                />
                <button type="button" class="btn" disabled={!sourceName.trim()} onclick={addSource}
                  ><Plus size={16} /> Add</button
                >
              </div>
            {/if}
          </section>

          <section aria-labelledby="quality-priorities-title">
            <div class="group-heading">
              <h3 id="quality-priorities-title">Quality priorities</h3>
              <span class="column-label">Higher = preferred</span>
            </div>
            <p class="hint">Added to the source priority for each video link.</p>
            <div class="priority-list">
              {#each QUALITIES as quality (quality.value)}
                <div
                  class={[
                    "priority-row",
                    {
                      changed:
                        draft !== null &&
                        (draft.qualityPriorities[quality.value] ?? quality.priority) !==
                          (active?.qualityPriorities[quality.value] ?? quality.priority),
                    },
                  ]}
                >
                  <div class="source-label">
                    <span>{quality.label}</span>
                    {#if draft && (draft.qualityPriorities[quality.value] ?? quality.priority) !== (active?.qualityPriorities[quality.value] ?? quality.priority)}<small
                        >Modified</small
                      >{/if}
                  </div>
                  {@render priorityControl(
                    quality.label,
                    shown.qualityPriorities[quality.value] ?? quality.priority,
                    (value) => {
                      if (draft) draft.qualityPriorities[quality.value] = value;
                    },
                  )}
                </div>
              {/each}
            </div>
          </section>
        </div>

        <section class="visibility" aria-labelledby="priority-visibility-title">
          <h3 id="priority-visibility-title">Visibility</h3>
          {#if draft}
            <label class="check">
              <span
                ><b>Hide failed sources</b><small
                  >Hide links that failed in this playback session.</small
                ></span
              >
              <input type="checkbox" role="switch" bind:checked={draft.hideFailedSources} />
            </label>
            <label class="check">
              <span
                ><b>Hide negative-priority sources</b><small
                  >Hide links with a negative total priority and skip them in auto-play.</small
                ></span
              >
              <input type="checkbox" role="switch" bind:checked={draft.hideNegativeSources} />
            </label>
          {:else}
            <div class="visibility-summary">
              <span>Hide failed sources</span><b>{shown.hideFailedSources ? "On" : "Off"}</b>
            </div>
            <div class="visibility-summary">
              <span>Hide negative-priority sources</span><b
                >{shown.hideNegativeSources ? "On" : "Off"}</b
              >
            </div>
          {/if}
          <p class="hint">The current stream always stays visible.</p>
        </section>
        {#if draft}
          <div class="reset-area">
            {#if resetBackup}
              <span class="hint">Profile reset to defaults.</span>
              <button
                class="reset"
                type="button"
                onclick={() => {
                  draft = resetBackup;
                  resetBackup = null;
                  announcement = "Reset undone.";
                }}>Undo reset</button
              >
            {:else}
              <button class="reset" type="button" onclick={resetProfile}
                ><RotateCcw size={15} /> Reset priorities and visibility</button
              >
            {/if}
          </div>
        {/if}
        <p class="announcement" role="status">{announcement}</p>
      </fieldset>
      {#if draft}
        <div class="save-bar">
          {#if error}<p class="error" role="alert">Could not save: {error}. Try again.</p>{/if}
          <div class="actions">
            <span class={["save-status", { pending: changeCount > 0 }]} role="status"
              >{saving
                ? "Saving profile…"
                : changeCount
                  ? `${changeCount} unsaved ${changeCount === 1 ? "change" : "changes"}`
                  : "No changes yet"}</span
            >
            <button
              class="btn"
              type="button"
              disabled={saving}
              onclick={() => {
                draft = null;
                error = null;
                announcement = "";
                resetBackup = null;
              }}>Cancel</button
            >
            <button class="btn primary" type="submit" disabled={saving || !changeCount}
              ><Check size={16} />{saving ? "Saving…" : "Save changes"}</button
            >
          </div>
        </div>
      {/if}
    </form>
  {/if}
  {#if error && !draft}<p class="error" role="alert">Could not save: {error}. Try again.</p>{/if}
</div>

<style>
  .profiles {
    min-width: 0;
    container-type: inline-size;
  }
  .profile-picker {
    display: flex;
    align-items: end;
    flex-wrap: wrap;
    gap: 12px;
  }
  .stack {
    display: grid;
    gap: 8px;
    font-size: 13px;
    color: var(--muted);
  }
  .profile-picker label {
    flex: 1 1 220px;
  }
  .profile-picker select {
    width: 100%;
  }
  .editing-status {
    padding: 12px 0;
    color: var(--accent);
    font-size: 13px;
  }
  .hint {
    color: var(--muted);
    font-size: 13px;
    line-height: 1.6;
    margin: 8px 0 0;
    max-width: 70ch;
  }
  .priority-guide {
    margin: 24px 0;
    padding: 16px 20px;
    border-radius: 12px;
    background: var(--surface-2);
  }
  .priority-guide summary {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    cursor: pointer;
    list-style: none;
    min-height: 44px;
  }
  .priority-guide summary::-webkit-details-marker {
    display: none;
  }
  .priority-guide summary span {
    display: grid;
    gap: 4px;
    font-size: 14px;
  }
  .priority-guide summary small {
    font-size: 13px;
    color: var(--muted);
    line-height: 1.5;
  }
  .priority-guide summary :global(svg) {
    flex-shrink: 0;
  }
  .priority-guide[open] summary :global(svg) {
    transform: rotate(180deg);
  }
  .preview-controls {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
    gap: 12px;
    margin-top: 20px;
  }
  .preview-controls select {
    width: 100%;
  }
  .preview-result {
    display: flex;
    flex-wrap: wrap;
    align-items: baseline;
    gap: 8px 20px;
    margin-top: 16px;
    font-size: 14px;
  }
  .preview-result strong {
    font-variant-numeric: tabular-nums;
    color: var(--accent);
  }
  .preview-result span {
    color: var(--muted);
    font-size: 13px;
  }
  .source-search {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-top: 16px;
    border: 1px solid var(--line);
    border-radius: 8px;
    padding-left: 12px;
    color: var(--muted);
  }
  .source-search input {
    width: 0;
    flex: 1;
    background: transparent;
    border: 0;
    min-height: 44px;
  }
  .source-search :global(svg) {
    flex-shrink: 0;
  }
  .search-count {
    color: var(--muted);
    font-size: 12px;
    margin: 8px 0 0;
  }
  .priority-row.changed .stepper {
    border-color: var(--accent);
  }
  .priority-row.changed .source-label small {
    color: var(--accent);
  }
  .reset-area {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 12px;
    margin-top: 20px;
  }
  .announcement {
    color: var(--muted);
    font-size: 13px;
    line-height: 1.5;
  }
  .announcement:empty {
    display: none;
  }
  .save-bar {
    position: sticky;
    bottom: calc(var(--nav-h) + var(--safe-b) + 12px);
    background: var(--surface-2);
    z-index: 5;
    margin: 24px 0 12px;
    padding: 12px;
    border-radius: 12px;
    box-shadow: 0 8px 24px rgb(0 0 0 / 0.3);
  }
  .save-status {
    margin-right: auto;
    font-size: 13px;
    line-height: 1.5;
    font-variant-numeric: tabular-nums;
    color: var(--muted);
  }
  .save-status.pending {
    color: var(--text);
  }
  .save-bar .error {
    margin: 0 0 12px;
    overflow-wrap: anywhere;
  }
  .save-bar .btn {
    min-height: 44px;
    background: transparent;
  }
  .save-bar .btn:hover:not(:disabled) {
    background: var(--surface-3);
  }
  .save-bar .btn.primary {
    background: var(--accent);
  }
  .save-bar .btn.primary:disabled {
    background: var(--surface-3);
    color: var(--muted);
    opacity: 1;
    filter: none;
  }
  :global(.shell.with-bar) .save-bar {
    bottom: calc(var(--nav-h) + var(--bar-h) + var(--safe-b) + 12px);
  }
  @media (min-width: 900px) {
    .save-bar {
      bottom: 12px;
    }
    :global(.shell.with-bar) .save-bar {
      bottom: calc(var(--bar-h) + 12px);
    }
  }
  @container (max-width: 450px) {
    .save-status {
      grid-column: 1 / -1;
    }
    .save-bar .actions {
      display: grid;
      grid-template-columns: minmax(0, 1fr) minmax(0, 1.35fr);
    }
    .save-bar .btn {
      padding-inline: 12px;
    }
    .preview-controls {
      grid-template-columns: minmax(0, 1fr);
    }
  }
  .profiles.in-sources .save-bar {
    bottom: 8px;
  }
  .priority-guide .hint {
    margin-top: 4px;
  }
  .priority-guide strong {
    font-weight: 600;
  }
  fieldset {
    border: 0;
    margin: 0;
    padding: 0;
    min-width: 0;
  }
  .profile-name {
    max-width: 420px;
    margin-bottom: 28px;
  }
  .priority-columns {
    display: grid;
    grid-template-columns: minmax(0, 1fr);
    gap: 32px;
  }
  section {
    min-width: 0;
  }
  h3 {
    font-size: 15px;
    font-weight: 600;
    margin: 0;
  }
  .group-heading {
    display: flex;
    justify-content: space-between;
    align-items: baseline;
    gap: 12px;
  }
  .column-label {
    font-size: 12px;
    color: var(--muted);
  }
  .priority-list {
    margin-top: 16px;
    border-top: 1px solid var(--line);
  }
  .priority-row {
    display: flex;
    align-items: center;
    gap: 12px;
    min-height: 58px;
    padding: 6px 0;
    border-bottom: 1px solid var(--line);
  }
  .priority-row > span:first-child,
  .source-label {
    flex: 1;
    min-width: 0;
    overflow-wrap: anywhere;
    font-size: 14px;
  }
  .source-label {
    display: grid;
    gap: 3px;
  }
  .source-label small {
    font-size: 11px;
    color: var(--muted);
  }
  .score {
    font-size: 14px;
    font-variant-numeric: tabular-nums;
    text-align: center;
    min-width: 48px;
  }
  .stepper {
    display: flex;
    align-items: center;
    flex-shrink: 0;
    border: 1px solid var(--line);
    border-radius: 8px;
    background: var(--surface-2);
  }
  .stepper button,
  .icon-button {
    display: inline-flex;
    justify-content: center;
    align-items: center;
    min-width: 36px;
    min-height: 44px;
    border-radius: 7px;
    color: var(--muted);
  }
  .stepper button:hover:not(:disabled),
  .icon-button:hover:not(:disabled),
  .reset:hover:not(:disabled) {
    color: var(--text);
    background: var(--surface-3);
  }
  .stepper input {
    width: 58px;
    min-width: 0;
    min-height: 44px;
    padding: 4px 0;
    border: 0;
    background: transparent;
    text-align: center;
    font-variant-numeric: tabular-nums;
    border-radius: 0;
    appearance: textfield;
    -moz-appearance: textfield;
  }
  .stepper input::-webkit-inner-spin-button,
  .stepper input::-webkit-outer-spin-button {
    -webkit-appearance: none;
    margin: 0;
  }
  .stepper input:focus-visible {
    outline-offset: -2px;
  }
  button:disabled {
    opacity: 0.4;
    cursor: not-allowed;
  }
  .empty-sources {
    padding: 24px 0;
  }
  .empty-sources strong {
    font-size: 14px;
    font-weight: 500;
  }
  .default-value {
    display: inline-flex;
    gap: 16px;
    align-items: center;
    font-size: 12px;
    color: var(--muted);
    margin-top: 16px;
  }
  .default-value b {
    color: var(--text);
    font-variant-numeric: tabular-nums;
  }
  .add-label {
    margin-top: 20px;
  }
  .add-source {
    display: flex;
    gap: 8px;
    margin-top: 8px;
  }
  .add-source input {
    width: 0;
    min-width: 0;
    flex: 1;
  }
  .visibility {
    margin-top: 32px;
    padding-top: 24px;
    border-top: 1px solid var(--line);
  }
  .visibility h3 {
    margin-bottom: 12px;
  }
  .check,
  .visibility-summary {
    display: flex;
    justify-content: space-between;
    gap: 20px;
    align-items: center;
    min-height: 56px;
    font-size: 14px;
    padding: 10px 0;
  }
  .check {
    cursor: pointer;
  }
  .check span {
    display: grid;
    gap: 4px;
  }
  .check b,
  .visibility-summary b {
    font-weight: 500;
  }
  .check small {
    color: var(--muted);
    font-size: 13px;
    line-height: 1.5;
  }
  .check input {
    appearance: none;
    flex-shrink: 0;
    width: 46px;
    height: 28px;
    padding: 0;
    border: 1px solid var(--muted);
    border-radius: 999px;
    background: var(--surface-3);
    position: relative;
    cursor: pointer;
  }
  .check input::after {
    content: "";
    position: absolute;
    top: 2px;
    left: 2px;
    width: 22px;
    height: 22px;
    border-radius: 50%;
    background: var(--text);
  }
  .check input:checked {
    background: var(--accent);
    border-color: var(--accent);
  }
  .check input:checked::after {
    transform: translateX(18px);
  }
  .check input:disabled {
    opacity: 0.5;
    cursor: wait;
  }
  .visibility-summary {
    min-height: 40px;
  }
  .visibility-summary b {
    color: var(--muted);
  }
  .actions {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    align-items: center;
  }
  .reset {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    min-height: 44px;
    color: var(--muted);
    padding: 8px;
    border-radius: 8px;
    font-size: 13px;
  }
  .error {
    color: var(--danger);
    font-size: 13px;
    margin-top: 16px;
  }
  @container (min-width: 720px) {
    .priority-columns {
      grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
      gap: 32px;
    }
  }
  @container (max-width: 300px) {
    .source-row {
      flex-wrap: wrap;
      gap: 8px;
    }
    .source-label {
      flex-basis: calc(100% - 52px);
    }
    .source-row .stepper {
      order: 2;
      margin-bottom: 4px;
    }
    .reset {
      margin-left: 0;
    }
  }
</style>
