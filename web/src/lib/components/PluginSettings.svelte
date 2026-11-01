<script lang="ts">
  import { api } from "../api";
  import { toasts } from "../toast.svelte";
  import type { PrefEntry } from "../types";

  // A generic key/value form over the plugin's preference files. Keys appear once the plugin has read them, or
  // can be added by hand (the Android settings dialogs aren't available on the desktop).
  let { name }: { name: string } = $props();
  let files = $state<Record<string, PrefEntry[]> | null>(null);
  let saving = $state(false);

  $effect(() => {
    api.pluginSettings(name).then((r) => (files = r.files)).catch(toasts.error);
  });

  function add(file: string) {
    files![file].push({ key: "", type: "string", value: "" });
  }

  async function save() {
    if (!files) return;
    saving = true;
    try {
      const clean = Object.fromEntries(
        Object.entries(files).map(([f, entries]) => [f, entries.filter((e) => e.key.trim() && e.value !== null && e.value !== "")]),
      );
      files = (await api.savePluginSettings(name, clean)).files;
      toasts.show(`Saved ${name} settings`);
    } catch (e) {
      toasts.error(e);
    } finally {
      saving = false;
    }
  }
</script>

{#if files}
  <div class="form">
    {#each Object.entries(files) as [file, entries] (file)}
      {#if Object.keys(files).length > 1}<div class="file">{file}</div>{/if}
      {#each entries as e, i (i)}
        <div class="row">
          <input class="key" bind:value={e.key} placeholder="key" aria-label="Key" readonly={e.default != null} />
          {#if e.type === "boolean"}
            <select bind:value={e.value} aria-label="Value">
              <option value="">default</option>
              <option value="true">true</option>
              <option value="false">false</option>
            </select>
          {:else}
            <input
              class="val"
              bind:value={e.value}
              placeholder={e.default ? `default: ${e.default}` : "unset"}
              type={/key|token|secret|password/i.test(e.key) ? "password" : "text"}
              aria-label="Value of {e.key}"
            />
          {/if}
        </div>
      {/each}
      {#if !entries.length}<p class="muted small">This plugin hasn't read any settings yet. Use it once, or add a key by hand.</p>{/if}
      <button class="link-btn" onclick={() => add(file)}>+ Add key</button>
    {/each}
    <div><button class="btn primary" onclick={save} disabled={saving}>Save</button></div>
  </div>
{:else}
  <div class="spinner"></div>
{/if}

<style>
  .form {
    display: flex;
    flex-direction: column;
    gap: 8px;
    padding-top: 8px;
  }
  .file {
    font-size: 12px;
    font-weight: 700;
    color: var(--faint);
    text-transform: uppercase;
  }
  .row {
    display: grid;
    grid-template-columns: minmax(0, 2fr) minmax(0, 3fr);
    gap: 8px;
  }
  .key {
    font-family: ui-monospace, monospace;
    font-size: 13px;
  }
  .link-btn {
    align-self: flex-start;
    color: var(--accent);
    font-size: 14px;
    font-weight: 600;
  }
  .small {
    font-size: 13px;
    margin: 0;
  }
</style>
