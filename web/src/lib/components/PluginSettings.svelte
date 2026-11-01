<script lang="ts">
  import { api } from "../api";
  import { toasts } from "../toast.svelte";
  import type { PrefEntry, SettingField } from "../types";

  // The plugin's declared settings (its schema) as labelled fields, then a generic key/value form over its
  // preference files for every other key. Keys appear once the plugin has read them, or can be added by hand (the
  // Android settings dialogs aren't available on the desktop).
  let { name, schema = [] }: { name: string; schema?: SettingField[] } = $props();
  let files = $state<Record<string, PrefEntry[]> | null>(null);
  let saving = $state(false);
  let revealed = $state<string[]>([]);

  const prefType = (f: SettingField) =>
    f.type === "boolean" ? "boolean" : f.type === "int" ? "int" : "string";
  const fieldId = (f: SettingField) => `${f.file}/${f.key}`;

  /** Every schema field gets an entry to bind to, even before the plugin has read or saved it. */
  function withSchema(loaded: Record<string, PrefEntry[]>) {
    for (const f of schema) {
      const file = f.file ?? Object.keys(loaded)[0] ?? name;
      loaded[file] ??= [];
      if (!loaded[file].some((e) => e.key === f.key))
        loaded[file].push({ key: f.key, type: prefType(f), value: null, default: f.default });
    }
    return loaded;
  }

  $effect(() => {
    api
      .pluginSettings(name)
      .then((r) => (files = withSchema(r.files)))
      .catch(toasts.error);
  });

  function entry(f: SettingField) {
    return files?.[f.file ?? ""]?.find((e) => e.key === f.key);
  }

  function setValue(f: SettingField, v: string | null) {
    const e = entry(f);
    if (e) e.value = v;
  }

  const declared = $derived(new Set(schema.map(fieldId)));

  function add(file: string) {
    files![file].push({ key: "", type: "string", value: "" });
  }

  async function save() {
    if (!files) return;
    saving = true;
    try {
      const clean = Object.fromEntries(
        Object.entries(files).map(([f, entries]) => [
          f,
          entries.filter((e) => e.key.trim() && e.value !== null && e.value !== ""),
        ]),
      );
      files = withSchema((await api.savePluginSettings(name, clean)).files);
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
    {#each schema as f (fieldId(f))}
      {@const value = entry(f)?.value ?? ""}
      <div class="field">
        {#if f.type === "boolean"}
          <label class="toggle">
            <span
              ><b>{f.label}</b>{#if f.help}<small>{f.help}</small>{/if}</span
            >
            <input
              type="checkbox"
              checked={value === "" ? f.default === "true" : value === "true"}
              onchange={(e) => setValue(f, String(e.currentTarget.checked))}
            />
          </label>
        {:else}
          <label class="stack">
            <b>{f.label}</b>
            {#if f.help}<small>{f.help}</small>{/if}
            {#if f.type === "choice"}
              <select
                value={value || f.default || ""}
                onchange={(e) => setValue(f, e.currentTarget.value)}
              >
                {#if !f.default}<option value="">Not set</option>{/if}
                {#each f.options as o (o.value)}<option value={o.value}>{o.label}</option>{/each}
              </select>
            {:else}
              <span class="secret">
                <input
                  type={f.type === "int"
                    ? "number"
                    : f.type === "secret" && !revealed.includes(fieldId(f))
                      ? "password"
                      : "text"}
                  {value}
                  placeholder={f.default ? `Default: ${f.default}` : "Not set"}
                  oninput={(e) => setValue(f, e.currentTarget.value)}
                  autocomplete="off"
                />
                {#if f.type === "secret"}
                  <button
                    class="link-btn"
                    type="button"
                    onclick={() =>
                      (revealed = revealed.includes(fieldId(f))
                        ? revealed.filter((id) => id !== fieldId(f))
                        : [...revealed, fieldId(f)])}
                    >{revealed.includes(fieldId(f)) ? "Hide" : "Show"}</button
                  >
                {/if}
              </span>
            {/if}
          </label>
        {/if}
      </div>
    {/each}
    {#if schema.length}<div class="file other">Other</div>{/if}
    {#each Object.entries(files) as [file, entries] (file)}
      {#if Object.keys(files).length > 1}<div class="file">{file}</div>{/if}
      {#each entries as e, i (i)}
        {#if !declared.has(`${file}/${e.key}`)}
          <div class="row">
            <input
              class="key"
              bind:value={e.key}
              placeholder="key"
              aria-label="Key"
              readonly={e.default != null}
            />
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
        {/if}
      {/each}
      {#if !entries.length}<p class="muted small">
          This plugin hasn't read any settings yet. Use it once, or add a key by hand.
        </p>{/if}
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
  .field + .file.other {
    margin-top: 6px;
  }
  .toggle {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    cursor: pointer;
  }
  .toggle span,
  .stack {
    display: flex;
    flex-direction: column;
    gap: 3px;
  }
  small {
    color: var(--muted);
    font-size: 13px;
  }
  .secret {
    display: flex;
    gap: 10px;
    align-items: center;
  }
  .secret input {
    flex: 1;
    min-width: 0;
  }
  .stack select {
    width: 100%;
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
