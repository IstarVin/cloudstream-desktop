<script lang="ts">
  import { useQueryClient } from "@tanstack/svelte-query";
  import { api } from "../api";
  import { QUALITIES } from "../ranking";
  import { toasts } from "../toast.svelte";
  import type { Settings, SourceProfile } from "../types";

  let { settings, knownSources = [] }: { settings: Settings; knownSources?: string[] } = $props();
  const client = useQueryClient();
  const active = $derived(settings.source_profiles.find((p) => p.id === settings.source_profile));
  let draft = $state<SourceProfile | null>(null);
  let saving = $state(false);
  let error = $state<string | null>(null);
  let sourceName = $state("");
  const names = $derived(
    [
      ...new Set([
        ...knownSources,
        ...settings.source_profiles.flatMap((profile) => Object.keys(profile.sourcePriorities)),
        ...Object.keys(draft?.sourcePriorities ?? {}),
      ]),
    ].sort(),
  );

  function edit() {
    if (!active) return;
    draft = {
      ...active,
      sourcePriorities: { ...active.sourcePriorities },
      qualityPriorities: { ...active.qualityPriorities },
    };
    sourceName = "";
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
    if (!draft) return;
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
    draft.sourcePriorities[name] ??= 1;
    sourceName = "";
  }
</script>

<div class="profiles">
  <div class="profile-picker">
    <label>
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
    {#if !draft}<button class="btn" onclick={edit} disabled={saving || !active}>Edit profile</button
      >{/if}
  </div>
  <p class="hint">
    Applies to auto-play, fallback and source pickers. Changing profiles keeps the current stream
    playing.
  </p>

  {#if draft}
    <form onsubmit={saveProfile}>
      <fieldset disabled={saving}>
        <label class="stack"
          >Profile name <input
            bind:value={draft.name}
            maxlength="80"
            placeholder="Profile {draft.id}"
          /></label
        >
        <p class="hint">
          Source priority + quality priority = video priority. Higher totals play first. A total of
          10 or more starts auto-play immediately; other links use the usual wait settings.
        </p>

        <h3>Source priorities</h3>
        <p class="hint">Unlisted sources have priority 1. Names match the source labels exactly.</p>
        {#each names as name (name)}
          <label class="priority-row">
            <span>{name}</span>
            <input
              type="number"
              min="-1000"
              max="1000"
              step="1"
              required
              aria-label="Priority for {name}"
              value={draft.sourcePriorities[name] ?? 1}
              onchange={(e) => {
                if (draft) draft.sourcePriorities[name] = Number(e.currentTarget.value);
              }}
            />
          </label>
        {/each}
        {#if !names.length}<p class="hint">
            Open an episode’s sources to see its names here, or add one below.
          </p>{/if}
        <div class="add-source">
          <input
            bind:value={sourceName}
            maxlength="200"
            placeholder="Exact source name"
            aria-label="Source name to add"
            onkeydown={(e) => {
              if (e.key === "Enter") {
                e.preventDefault();
                addSource();
              }
            }}
          />
          <button type="button" class="btn" disabled={!sourceName.trim()} onclick={addSource}
            >Add source</button
          >
        </div>

        <h3>Quality priorities</h3>
        {#each QUALITIES as quality (quality.value)}
          <label class="priority-row">
            <span>{quality.label}</span>
            <input
              type="number"
              min="-1000"
              max="1000"
              step="1"
              required
              aria-label="Priority for {quality.label}"
              value={draft.qualityPriorities[quality.value] ?? quality.priority}
              onchange={(e) => {
                if (draft) draft.qualityPriorities[quality.value] = Number(e.currentTarget.value);
              }}
            />
          </label>
        {/each}

        <h3>Visibility</h3>
        <label class="check"
          ><input type="checkbox" bind:checked={draft.hideFailedSources} /> Hide links that failed in
          this playback session</label
        >
        <label class="check"
          ><input type="checkbox" bind:checked={draft.hideNegativeSources} /> Hide links with a negative
          total priority and skip them in auto-play</label
        >
        <p class="hint">The current stream always stays visible.</p>
        <div class="actions">
          <button class="btn primary" type="submit">{saving ? "Saving…" : "Save profile"}</button>
          <button
            class="btn"
            type="button"
            onclick={() => {
              draft = null;
              error = null;
            }}>Cancel</button
          >
          <button
            class="reset"
            type="button"
            onclick={() => {
              if (draft) {
                draft.sourcePriorities = {};
                draft.qualityPriorities = {};
                draft.hideFailedSources = false;
                draft.hideNegativeSources = false;
              }
            }}>Reset priorities</button
          >
        </div>
      </fieldset>
    </form>
  {/if}
  {#if error}<p class="error" role="alert">Could not save: {error}. Try again.</p>{/if}
</div>

<style>
  .profiles {
    min-width: 0;
  }
  .profile-picker {
    display: flex;
    align-items: end;
    flex-wrap: wrap;
    gap: 10px;
  }
  .profile-picker label {
    flex: 1 1 180px;
    display: grid;
    gap: 5px;
    font-size: 13px;
    color: var(--muted);
  }
  .profile-picker select {
    width: 100%;
  }
  .hint {
    color: var(--muted);
    font-size: 13px;
    line-height: 1.5;
    margin: 8px 0;
  }
  form {
    margin-top: 16px;
    padding-top: 16px;
    border-top: 1px solid var(--line);
  }
  fieldset {
    border: 0;
    margin: 0;
    padding: 0;
    min-width: 0;
  }
  .stack {
    display: grid;
    gap: 5px;
  }
  h3 {
    font-size: 14px;
    margin: 24px 0 8px;
  }
  .priority-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    padding: 5px 0;
  }
  .priority-row span {
    overflow-wrap: anywhere;
    min-width: 0;
  }
  .priority-row input {
    width: 90px;
    flex-shrink: 0;
    text-align: center;
    font-variant-numeric: tabular-nums;
  }
  .add-source {
    display: flex;
    gap: 8px;
    margin-top: 8px;
    flex-wrap: wrap;
  }
  .add-source input {
    flex: 1 1 150px;
  }
  .check {
    display: flex;
    gap: 10px;
    align-items: start;
    margin: 12px 0;
    font-size: 13px;
  }
  .check input {
    accent-color: var(--accent);
    margin: 3px 0 0;
    flex-shrink: 0;
  }
  .actions {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    align-items: center;
    margin-top: 20px;
  }
  .reset {
    min-height: 44px;
    color: var(--accent);
    padding: 8px;
  }
  .error {
    color: var(--danger);
    font-size: 13px;
  }
</style>
